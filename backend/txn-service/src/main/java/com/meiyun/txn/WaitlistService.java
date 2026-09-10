package com.meiyun.txn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.txn.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 排队智能候补领域服务（P5-B29 卡①）：候补登记、取消、到场转正式到店登记、号源释放自动递补通知。
 *
 * <p>铁律：操作人/门店一律取 {@link DataScope} 当前登录上下文（请求体 operator/storeCode 不可信）；
 * 客户优先按手机号经 {@link CustomerDirectoryClient} 反查锚定（与到店核销登记同口径），
 * 未命中允许散客快照但 fulfill 前必须补锚定（正式到店登记强制客户存在）；明文手机号不落库（掩码 138****2046）；
 * 全动作审计（bizType=WAITLIST，手工拼合法 JSON payload）；非法入参中文 4xx，越权统一 404。
 *
 * <p>自动递补 {@link #promoteNext} 由超时释放 Job 在无登录上下文下调用：仅置 NOTIFIED + 站内通知本店店长，
 * 不自动建队（防客户不到场误挂队列）；客户真实到场后由前台在 HTTP 上下文调 fulfill 转正式到店登记。
 */
@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    public static final String ST_WAITING = "WAITING";
    public static final String ST_NOTIFIED = "NOTIFIED";
    public static final String ST_FULFILLED = "FULFILLED";
    public static final String ST_CANCELLED = "CANCELLED";

    private static final List<String> ACTIVE = List.of(ST_WAITING, ST_NOTIFIED);

    private final ArrivalWaitlistRepository wlRepo;
    private final ArrivalNoGenerator noGen;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;
    private final CustomerDirectoryClient customerDirectoryClient;
    private final ArrivalService arrivalService;
    private final NotificationRepository notificationRepo;
    private final NotifyPreferenceRepository preferenceRepo;
    private final OrgStaffClient orgStaffClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WaitlistService(ArrivalWaitlistRepository wlRepo, ArrivalNoGenerator noGen, AuditRecorder audit,
                           ApptRefNameResolver names, CustomerDirectoryClient customerDirectoryClient,
                           ArrivalService arrivalService, NotificationRepository notificationRepo,
                           NotifyPreferenceRepository preferenceRepo, OrgStaffClient orgStaffClient) {
        this.wlRepo = wlRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.names = names;
        this.customerDirectoryClient = customerDirectoryClient;
        this.arrivalService = arrivalService;
        this.notificationRepo = notificationRepo;
        this.preferenceRepo = preferenceRepo;
        this.orgStaffClient = orgStaffClient;
    }

    // ==================== 候补登记 ====================

    /**
     * 候补登记：门店取 JWT 当前本店；手机号反查客户自动锚定（显式 customerId 优先且须存在），
     * 未命中落散客快照。同店同客户/同掩码手机号仍有活跃候补 → 409 防重复排队。
     */
    @Transactional
    public ArrivalWaitlist register(String customerIdInput, String customerName, String phone,
                                    String project, LocalDate expectDate, String note) {
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
            throw badRequest("候补项目不能为空");
        }
        if (storeCode == null || storeCode.isBlank()) {
            throw badRequest("当前登录人未归属门店，无法登记候补");
        }
        if (!names.storeNames(List.of(storeCode)).containsKey(storeCode)) {
            throw badRequest("门店不存在或已停用: " + storeCode);
        }
        String name = customerName.trim();
        String mobile = phone.trim();
        String proj = project.trim();
        String masked = CheckinService.maskPhone(mobile);

        String customerId = null;
        if (customerIdInput != null && !customerIdInput.isBlank()) {
            customerId = customerIdInput.trim();
            if (!names.customerNames(List.of(customerId)).containsKey(customerId)) {
                throw badRequest("客户不存在: " + customerId);
            }
        } else {
            Optional<CustomerDirectoryClient.Directory> dir = customerDirectoryClient.findByPhone(mobile, storeCode);
            customerId = dir.map(CustomerDirectoryClient.Directory::customerId).orElse(null);
        }

        if (customerId != null) {
            if (!wlRepo.findByStoreCodeAndCustomerIdAndStatusIn(storeCode, customerId, ACTIVE).isEmpty()) {
                throw conflict("该客户在本店已有进行中的候补登记，请勿重复提交");
            }
        } else if (!wlRepo.findByStoreCodeAndPhoneAndStatusIn(storeCode, masked, ACTIVE).isEmpty()) {
            throw conflict("该手机号在本店已有进行中的候补登记，请勿重复提交");
        }

        ArrivalWaitlist w = new ArrivalWaitlist();
        w.setWlNo(noGen.nextWlNo());
        w.setStoreCode(storeCode);
        w.setCustomerId(customerId);
        w.setCustomerName(name);
        w.setPhone(masked);
        w.setProject(proj);
        w.setExpectDate(expectDate);
        w.setStatus(ST_WAITING);
        w.setOperator(actor);
        if (note != null && !note.isBlank()) {
            w.setNote(note.trim());
        }
        w.setTimeline("[]");
        appendTimeline(w, actor, "登记候补（" + proj
                + (expectDate == null ? "，期望尽快" : "，期望 " + expectDate) + "）");
        ArrivalWaitlist saved = wlRepo.save(w);
        audit.record("WAITLIST", saved.getWlNo(), actor, "REGISTER",
                "{\"store\":\"" + storeCode + "\",\"project\":\"" + esc(proj)
                        + "\",\"customer\":" + (customerId == null ? "\"SNAPSHOT\"" : "\"" + customerId + "\"")
                        + ",\"anchored\":" + (customerId != null)
                        + ",\"expectDate\":" + (expectDate == null ? "null" : "\"" + expectDate + "\"") + "}");
        return saved;
    }

    /** 取消候补：WAITING/NOTIFIED → CANCELLED（终态幂等：已取消直接返回当前态）。 */
    @Transactional
    public ArrivalWaitlist cancel(String wlNo) {
        ArrivalWaitlist w = requireWaitlist(wlNo);
        String actor = DataScope.currentActor();
        if (ST_CANCELLED.equals(w.getStatus()) || ST_FULFILLED.equals(w.getStatus())) {
            throw badRequest("候补已" + (ST_CANCELLED.equals(w.getStatus()) ? "取消" : "到场完成") + "，不可重复操作");
        }
        String from = ST_NOTIFIED.equals(w.getStatus()) ? ST_NOTIFIED : ST_WAITING;
        w.setStatus(ST_CANCELLED);
        appendTimeline(w, actor, "取消候补");
        ArrivalWaitlist saved = wlRepo.save(w);
        audit.record("WAITLIST", wlNo, actor, "CANCEL",
                "{\"from\":\"" + from + "\",\"to\":\"CANCELLED\"}");
        return saved;
    }

    /**
     * 通知后客户到场 → 转正式到店登记（Arrival，channel=APPOINTMENT 之外的 WALK_IN/REFERRAL/MARKETING 三选一，
     * 默认 WALK_IN 并在 note 标注候补来源）。须已锚定客户（散客快照先建档）；WAITING 也允许前台直接确认到场。
     * 同事务置 FULFILLED 并回写 ah_no。
     */
    @Transactional
    public ArrivalWaitlist fulfill(String wlNo, String channel) {
        ArrivalWaitlist w = requireWaitlist(wlNo);
        String actor = DataScope.currentActor();
        LoginUser user = DataScope.current();
        String storeCode = user == null ? null : user.storeCode();
        if (ST_FULFILLED.equals(w.getStatus())) {
            throw badRequest("该候补已到场完成（到店登记号 " + w.getAhNo() + "）");
        }
        if (ST_CANCELLED.equals(w.getStatus())) {
            throw badRequest("该候补已取消，不可确认到场");
        }
        if (storeCode == null || !storeCode.equals(w.getStoreCode())) {
            // 候补到场必须在候补所属门店本店操作（区域账号也不跨店代操作，避免误建他店队列）
            throw badRequest("候补须在登记门店确认到场：" + w.getStoreCode());
        }
        if (w.getCustomerId() == null || w.getCustomerId().isBlank()) {
            throw badRequest("该候补尚未锚定客户档案，请先在客情建档后再确认到场");
        }
        String ch = channel == null || channel.isBlank()
                ? ArrivalService.CH_WALK_IN : channel.trim();
        if (!List.of(ArrivalService.CH_WALK_IN, ArrivalService.CH_REFERRAL, ArrivalService.CH_MARKETING).contains(ch)) {
            throw badRequest("非法到店渠道: " + ch);
        }
        Arrival arrival = arrivalService.create(w.getCustomerId(), ch,
                "候补到场（候补号 " + wlNo + "，项目 " + w.getProject() + "）");

        w.setStatus(ST_FULFILLED);
        w.setAhNo(arrival.getAhNo());
        appendTimeline(w, actor, "客户到场，已转正式到店登记 " + arrival.getAhNo()
                + "（排队号 " + arrival.getQueueNo() + "）");
        ArrivalWaitlist saved = wlRepo.save(w);
        audit.record("WAITLIST", wlNo, actor, "FULFILL",
                "{\"ahNo\":\"" + arrival.getAhNo() + "\",\"queueNo\":" + arrival.getQueueNo()
                        + ",\"channel\":\"" + ch + "\",\"customer\":\"" + w.getCustomerId() + "\"}");
        return saved;
    }

    // ==================== 自动递补（Job 调用，无登录上下文） ====================

    /**
     * 号源释放后自动递补：取同店最早 WAITING 一条置 NOTIFIED + 通知本店店长（站内信，幂等）。
     * 不自动建队；org 不可用/店长关闭 SYSTEM 类订阅时本轮不递补（下轮自愈）。返回是否完成递补。
     * 独立事务（Job 单条调用，经 Spring 代理生效）。
     */
    @Transactional
    public boolean promoteNext(String storeCode) {
        if (storeCode == null || storeCode.isBlank()) {
            return false;
        }
        ArrivalWaitlist w = wlRepo.findFirstByStoreCodeAndStatusOrderByCreatedAtAsc(storeCode, ST_WAITING);
        if (w == null) {
            return false;
        }
        List<OrgStaffClient.StaffBrief> managers = orgStaffClient.listStaffByRole("STORE_MGR", storeCode, null);
        int notified = 0;
        for (OrgStaffClient.StaffBrief m : managers) {
            String staffId = m.staffId();
            if (preferenceRepo.findByStaffIdAndCategory(staffId, "SYSTEM")
                    .filter(p -> !p.isEnabled()).isPresent()) {
                continue;
            }
            String idemKey = "WL:" + w.getWlNo() + ":" + staffId;
            if (notificationRepo.existsByIdemKey(idemKey)) {
                notified++;
                continue;
            }
            Notification n = new Notification();
            n.setRecipient(staffId);
            n.setCategory("SYSTEM");
            n.setLevel("INFO");
            n.setTitle("候补递补通知：" + w.getProject());
            n.setContent(truncate("候补号 " + w.getWlNo() + "（" + w.getCustomerName()
                    + "，手机 " + w.getPhone() + "）可递补号源，请尽快联系客户确认到店。", 500));
            n.setLink("/queue");
            n.setBizRef(w.getWlNo());
            n.setSender("system");
            n.setIdemKey(idemKey);
            n.setCreatedAt(OffsetDateTime.now());
            notificationRepo.save(n);
            notified++;
        }
        if (notified == 0) {
            log.warn("候补递补无可用通知目标，本轮保持 WAITING wlNo={} store={}", w.getWlNo(), storeCode);
            return false;
        }
        w.setStatus(ST_NOTIFIED);
        w.setNotifiedAt(OffsetDateTime.now());
        appendTimeline(w, "system", "号源释放，已自动递补并通知本店，等待客户到场");
        wlRepo.save(w);
        audit.record("WAITLIST", w.getWlNo(), "system", "PROMOTE",
                "{\"store\":\"" + storeCode + "\",\"notified\":" + notified + "}");
        log.info("候补自动递补 wlNo={} store={} 通知{}人", w.getWlNo(), storeCode, notified);
        return true;
    }

    // ==================== 查询 ====================

    /** 候补列表：数据域强制门店注入 + 门店/状态/期望日期过滤，登记时间正序（FIFO 顺位）。 */
    public List<ArrivalWaitlist> list(LocalDate expectDate, String storeCode, String status) {
        if (storeCode != null && !storeCode.isBlank() && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<ArrivalWaitlist> spec = DataScope.storeSpec("storeCode");
        if (expectDate != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("expectDate"), expectDate));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return wlRepo.findAll(spec, Sort.by(Sort.Order.asc("createdAt")));
    }

    // ==================== 内部 ====================

    private ArrivalWaitlist requireWaitlist(String wlNo) {
        ArrivalWaitlist w = wlNo == null ? null : wlRepo.findById(wlNo).orElse(null);
        if (w == null || !DataScope.canReadStore(w.getStoreCode())) {
            throw notFound("数据不存在或无权查看");
        }
        return w;
    }

    /** timeline 追加一条（by/text/at），JSON 数组落库（时间正序）。 */
    private void appendTimeline(ArrivalWaitlist w, String by, String text) {
        List<Map<String, String>> lines = readTimeline(w.getTimeline());
        Map<String, String> line = new LinkedHashMap<>();
        line.put("by", by);
        line.put("text", text);
        line.put("at", OffsetDateTime.now().toString());
        lines.add(line);
        try {
            w.setTimeline(objectMapper.writeValueAsString(lines));
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
