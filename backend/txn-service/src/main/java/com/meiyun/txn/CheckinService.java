package com.meiyun.txn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M2-09 会员到店核销领域服务：到店登记（扫码/预约/直接到店）、确认核销、异常标记/解除、队列查询。
 *
 * <p>登记时按手机号经 {@link CustomerDirectoryClient} 反查锚定客户（本店优先、公海兜底）；未命中允许以
 * 「快照散客」建单（customer_id 空，姓名/掩码手机/项目为快照）。门店一律取 JWT 当前本店（不信入参）。
 *
 * <p>铁律：操作人一律取 {@link DataScope#currentActor()}（请求体 operator 不可信忽略）；
 * 明文手机号不落库（落库/出参均掩码 138****2046）；写动作全程审计（bizType=CHECKIN，JSON payload）；
 * 非法入参中文 4xx，越权统一 404 不泄露存在性。
 */
@Service
public class CheckinService {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_DONE = "DONE";
    public static final String ST_EXCEPTION = "EXCEPTION";

    public static final Map<String, String> METHOD_TEXT = Map.of(
            "SCAN", "扫码核销",
            "APPOINTMENT", "预约到店",
            "WALKIN", "直接到店");

    private static final Map<String, String> EXCEPTION_TEXT = Map.of(
            "NOT_SELF", "非本人",
            "ALREADY_DONE", "已核销",
            "NO_APPOINTMENT", "无预约",
            "INFO_MISMATCH", "信息不符");

    private final CheckinRecordRepository ciRepo;
    private final CheckinNoGenerator noGen;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;
    private final CustomerDirectoryClient customerDirectoryClient;
    private final AppointmentRepository apptRepo;
    private final AppointmentArrivalService appointmentArrivalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CheckinService(CheckinRecordRepository ciRepo, CheckinNoGenerator noGen, AuditRecorder audit,
                          ApptRefNameResolver names, CustomerDirectoryClient customerDirectoryClient,
                          AppointmentRepository apptRepo,
                          AppointmentArrivalService appointmentArrivalService) {
        this.ciRepo = ciRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.names = names;
        this.customerDirectoryClient = customerDirectoryClient;
        this.apptRepo = apptRepo;
        this.appointmentArrivalService = appointmentArrivalService;
    }

    // ==================== 到店登记 ====================

    /**
     * 登记到店：门店取 JWT 当前本店；按手机号反查锚定客户，未命中落快照散客（customer_id 空）。
     * 同一手机号当日未核销的 PENDING 单直接返回既有单（前台重复提交/网络重试幂等，不重复排队）。
     */
    @Transactional
    public CheckinRecord register(String customerName, String phone, String project, String method) {
        String actor = DataScope.currentActor();
        LoginUser user = DataScope.current();
        String storeCode = user == null ? null : user.storeCode();
        if (customerName == null || customerName.isBlank()) {
            throw badRequest("客户姓名不能为空");
        }
        if (phone == null || phone.isBlank()) {
            throw badRequest("手机号不能为空");
        }
        if (project == null || project.isBlank()) {
            throw badRequest("到店项目不能为空");
        }
        if (method == null || !METHOD_TEXT.containsKey(method)) {
            throw badRequest("到店方式非法（支持：扫码核销/预约到店/直接到店）");
        }
        if (storeCode == null || storeCode.isBlank()) {
            throw badRequest("当前登录人未归属门店，无法登记到店");
        }
        if (!names.storeNames(List.of(storeCode)).containsKey(storeCode)) {
            throw badRequest("门店不存在或已停用: " + storeCode);
        }
        String name = customerName.trim();
        String mobile = phone.trim();
        String proj = project.trim();

        String masked = maskPhone(mobile);
        List<CheckinRecord> pendingToday = ciRepo
                .findByStoreCodeAndPhoneAndStatusAndArrivedAtGreaterThanEqualOrderByArrivedAtDesc(
                        storeCode, masked, ST_PENDING,
                        LocalDate.now().atStartOfDay().atOffset(ZoneOffset.ofHours(8)));
        if (!pendingToday.isEmpty()) {
            return pendingToday.get(0);
        }

        Optional<CustomerDirectoryClient.Directory> dir = customerDirectoryClient.findByPhone(mobile, storeCode);
        String customerId = dir.map(CustomerDirectoryClient.Directory::customerId).orElse(null);

        // 卡③ 预约勾连：仅「预约到店」且手机号已锚定客户时，匹配本店当日最早一单「已预约」自动完成到店编排
        // （置到店+建划扣任务+建接待队列，与预约看板签到同事务同口径）。未命中不阻断真实到店，按纯到店登记落单。
        String linkedApptNo = null;
        String linkedWdNo = null;
        if ("APPOINTMENT".equals(method) && customerId != null) {
            List<Appointment> todays = apptRepo
                    .findByStoreCodeAndApptDateOrderByApptTimeAsc(storeCode, LocalDate.now());
            Appointment hit = todays.stream()
                    .filter(a -> customerId.equals(a.getCustomerId())
                            && "已预约".equals(a.getStatus()))
                    .findFirst().orElse(null);
            if (hit != null) {
                AppointmentArrivalService.ArrivalResult r = appointmentArrivalService.checkIn(hit);
                linkedApptNo = r.appointment().getApptNo();
                linkedWdNo = r.wdNo();
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        CheckinRecord t = new CheckinRecord();
        t.setCiNo(noGen.nextCiNo());
        t.setStoreCode(storeCode);
        t.setCustomerId(customerId);
        t.setCustomerName(name);
        t.setPhone(masked);
        t.setProject(proj);
        t.setMethod(method);
        t.setStatus(ST_PENDING);
        t.setExceptionReason("NONE");
        t.setApptNo(linkedApptNo);
        t.setWdNo(linkedWdNo);
        t.setArrivedAt(now);
        t.setOperator(actor);
        t.setTimeline("[]");
        appendTimeline(t, actor, METHOD_TEXT.get(method) + "登记到店，待确认");
        if (linkedApptNo != null) {
            appendTimeline(t, "系统", "已自动勾连预约 " + linkedApptNo
                    + (linkedWdNo != null ? "，并生成待划扣任务 " + linkedWdNo : "") + "（预约置已到店）");
        }
        CheckinRecord saved = ciRepo.save(t);
        audit.record("CHECKIN", saved.getCiNo(), actor, "REGISTER",
                "{\"method\":\"" + method + "\",\"store\":\"" + storeCode
                        + "\",\"customer\":" + (customerId == null ? "\"SNAPSHOT\"" : "\"" + customerId + "\"")
                        + ",\"customerName\":\"" + esc(name) + "\",\"project\":\"" + esc(proj)
                        + "\",\"anchored\":" + (customerId != null)
                        + (linkedApptNo == null ? "" : ",\"apptNo\":\"" + linkedApptNo + "\""
                                + (linkedWdNo == null ? "" : ",\"wdNo\":\"" + linkedWdNo + "\""))
                        + "}");
        return saved;
    }

    // ==================== 确认核销 / 异常 ====================

    /** 确认核销：PENDING → DONE，写 checkedAt。幂等：DONE 单重复确认直接返回当前态。 */
    @Transactional
    public CheckinRecord confirm(String ciNo) {
        CheckinRecord t = requireRecord(ciNo);
        String actor = DataScope.currentActor();
        if (ST_DONE.equals(t.getStatus())) {
            return t;
        }
        if (ST_EXCEPTION.equals(t.getStatus())) {
            throw badRequest("该记录处于异常状态，请先解除异常再核销");
        }
        t.setStatus(ST_DONE);
        t.setCheckedAt(OffsetDateTime.now());
        appendTimeline(t, actor, "核销确认完成");
        CheckinRecord saved = ciRepo.save(t);
        audit.record("CHECKIN", ciNo, actor, "CONFIRM",
                "{\"customer\":" + (t.getCustomerId() == null ? "\"SNAPSHOT\"" : "\"" + t.getCustomerId() + "\"")
                        + ",\"status\":\"DONE\"}");
        return saved;
    }

    /** 标记异常：DONE 不可标（PENDING/EXCEPTION 均可，EXCEPTION 允许改标原因，与前端 mock 口径一致）。 */
    @Transactional
    public CheckinRecord markException(String ciNo, String reason, String note) {
        CheckinRecord t = requireRecord(ciNo);
        if (ST_DONE.equals(t.getStatus())) {
            throw badRequest("已核销记录不可标记异常");
        }
        if (reason == null || !EXCEPTION_TEXT.containsKey(reason)) {
            throw badRequest("异常原因非法（支持：非本人/已核销/无预约/信息不符）");
        }
        String actor = DataScope.currentActor();
        t.setStatus(ST_EXCEPTION);
        t.setExceptionReason(reason);
        if (note != null && !note.isBlank()) {
            t.setNote(note.trim());
        }
        String text = "标记异常：" + EXCEPTION_TEXT.get(reason)
                + (note != null && !note.isBlank() ? "（" + note.trim() + "）" : "");
        appendTimeline(t, actor, text);
        CheckinRecord saved = ciRepo.save(t);
        audit.record("CHECKIN", ciNo, actor, "EXCEPTION",
                "{\"reason\":\"" + reason + "\",\"note\":\"" + esc(note == null ? "" : note.trim()) + "\"}");
        return saved;
    }

    /** 解除异常：EXCEPTION → PENDING，清异常原因/备注。 */
    @Transactional
    public CheckinRecord reset(String ciNo) {
        CheckinRecord t = requireRecord(ciNo);
        if (!ST_EXCEPTION.equals(t.getStatus())) {
            throw badRequest("仅异常状态记录可解除异常，当前: " + t.getStatus());
        }
        String actor = DataScope.currentActor();
        t.setStatus(ST_PENDING);
        t.setExceptionReason("NONE");
        t.setNote(null);
        appendTimeline(t, actor, "异常已解除，重新待确认");
        CheckinRecord saved = ciRepo.save(t);
        audit.record("CHECKIN", ciNo, actor, "RESET", "{}");
        return saved;
    }

    // ==================== 查询 ====================

    /** 队列查询：数据域强制注入 + 日期/门店/方式/状态过滤，按到店时间倒序（默认今日，上海日界）。 */
    public List<CheckinRecord> list(LocalDate date, String storeCode, String method, String status) {
        if (storeCode != null && !storeCode.isBlank() && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<CheckinRecord> spec = DataScope.storeSpec("storeCode");
        if (date != null) {
            OffsetDateTime start = date.atStartOfDay().atOffset(ZoneOffset.ofHours(8));
            OffsetDateTime end = date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8));
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("arrivedAt"), start));
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("arrivedAt"), end));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (method != null && !method.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("method"), method));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return ciRepo.findAll(spec, Sort.by(Sort.Order.desc("arrivedAt")));
    }

    // ==================== 内部 ====================

    private CheckinRecord requireRecord(String ciNo) {
        CheckinRecord t = ciNo == null ? null : ciRepo.findById(ciNo).orElse(null);
        if (t == null || !DataScope.canReadStore(t.getStoreCode())) {
            throw notFound("数据不存在或无权查看");
        }
        return t;
    }

    /** timeline 追加一条（by/text/at），JSON 数组落库（时间正序）。 */
    private void appendTimeline(CheckinRecord t, String by, String text) {
        List<Map<String, String>> lines = readTimeline(t.getTimeline());
        Map<String, String> line = new LinkedHashMap<>();
        line.put("by", by);
        line.put("text", text);
        line.put("at", OffsetDateTime.now().toString());
        lines.add(line);
        try {
            t.setTimeline(objectMapper.writeValueAsString(lines));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "时间线序列化失败");
        }
    }

    /** 读取 timeline JSON（空/脏数据降级为空列表）。 */
    public List<Map<String, String>> readTimeline(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 手机号脱敏：11 位保留前 3 后 4（138****2046）；其他长度保留后 4；空值回落 ****。 */
    static String maskPhone(String phone) {
        if (phone == null) return "****";
        String p = phone.trim();
        if (p.length() == 11) {
            return p.substring(0, 3) + "****" + p.substring(7);
        }
        if (p.length() > 4) {
            return "****" + p.substring(p.length() - 4);
        }
        return "****";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
