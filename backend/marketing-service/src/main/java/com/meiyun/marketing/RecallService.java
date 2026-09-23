package com.meiyun.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复诊召回服务（P5-B90，/recall 切真，DESIGN §3 Recall 七端点）。
 *
 * <p>状态机服务端唯一权威（与前端 recall.ts TRANSITIONS 逐字对齐）：
 * PENDING→[NOTIFIED,SKIPPED]；NOTIFIED→[CONFIRMED,BOOKED,SKIPPED,PENDING(改期回退)]；
 * CONFIRMED→[BOOKED,SKIPPED]；BOOKED/SKIPPED 终态。非法转移抛 409 中文（GlobalExceptionHandler 透传）。
 * 每次转移 append timeline（{at,by,action,detail} 中文动作文案与 mock 对齐）。
 *
 * <p>创建经 {@link CustomerDirectoryClient#requireCustomer} 硬校验回填姓名/门店；
 * notify 落 notified_by/notified_at；skip 落 skip_reason；reschedule 仅 PENDING/NOTIFIED 可改期
 * （NOTIFIED→PENDING 回退并清通知信息）。
 */
@Service
public class RecallService {

    private static final Logger log = LoggerFactory.getLogger(RecallService.class);
    private static final ZoneOffset BIZ_TZ = ZoneOffset.of("+08:00");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SOURCES = Set.of("DOCTOR_ADVICE", "COURSE_FOLLOW", "SYSTEM_AUTO", "MANUAL");
    private static final Set<String> METHODS = Set.of("PHONE", "WECHAT", "SMS", "IN_STORE");

    /** 状态机转移表（服务端唯一权威，与前端 recall.ts TRANSITIONS 对齐）。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PENDING", Set.of("NOTIFIED", "SKIPPED"),
            "NOTIFIED", Set.of("CONFIRMED", "BOOKED", "SKIPPED", "PENDING"),
            "CONFIRMED", Set.of("BOOKED", "SKIPPED"),
            "BOOKED", Set.of(),
            "SKIPPED", Set.of());

    private final RecallRepository recallRepo;
    private final CustomerDirectoryClient customerClient;
    private final BizNoGenerator bizNo;
    private final AuditRecorder audit;

    public RecallService(RecallRepository recallRepo, CustomerDirectoryClient customerClient,
                         BizNoGenerator bizNo, AuditRecorder audit) {
        this.recallRepo = recallRepo;
        this.customerClient = customerClient;
        this.bizNo = bizNo;
        this.audit = audit;
    }

    /** 召回视图（字段名对齐前端 recall.ts Recall 契约；id=recallNo）。 */
    public record RecallView(String id, String recallNo, String customerId, String customerName,
                             String source, String reason, String relatedEmrNo, String relatedOrderNo,
                             String lastVisitDate, String dueDate, String method, String status,
                             String notifiedByName, String notifiedAt, String customerReply,
                             String confirmedDate, String skipReason, String note,
                             String timeline, String createdAt, String ruleNo, String storeCode) {}

    /** KPI 四键（DESIGN §3：overdue/todayPending/upcoming/conversionRate，业务时区 +8 锚今日）。 */
    public record KpiView(long overdue, long todayPending, long upcoming, long conversionRate) {}

    public record ListResp(List<RecallView> recalls, KpiView kpi) {}

    /** 新建命令（source 四来源；dueDate=yyyy-MM-dd 必填；method 默认 PHONE）。 */
    public record ScheduleCmd(String customerId, String source, String reason, String relatedEmrNo,
                              String relatedOrderNo, String lastVisitDate, String dueDate,
                              String method, String note) {}

    public record NotifyCmd(String method) {}

    public record ConfirmCmd(String reply, String confirmedDate) {}

    public record DetailCmd(String detail) {}

    public record SkipCmd(String reason) {}

    public record RescheduleCmd(String dueDate, String note) {}

    /** 列表（status 精确；kw 模糊匹配姓名/事由/单号；storeCode 精确）＋全量 KPI。 */
    public ListResp list(String status, String kw, String storeCode) {
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (st != null && !TRANSITIONS.containsKey(st)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 不合法：" + status);
        }
        String keyword = kw == null || kw.isBlank() ? null : kw.trim();
        String sc = storeCode == null || storeCode.isBlank() ? null : storeCode.trim();
        List<Recall> all = recallRepo.findAllByOrderByCreatedAtDesc();
        List<RecallView> recalls = all.stream()
                .filter(r -> st == null || st.equals(r.getStatus()))
                .filter(r -> keyword == null
                        || contains(r.getCustomerName(), keyword)
                        || contains(r.getReason(), keyword)
                        || contains(r.getRecallNo(), keyword))
                .filter(r -> sc == null || sc.equals(r.getStoreCode()))
                .map(RecallService::toView)
                .toList();
        return new ListResp(recalls, kpi(all));
    }

    /** 新建复诊提醒（PENDING；客户硬校验；timeline 首条「创建复诊提醒」）。 */
    public RecallView schedule(ScheduleCmd cmd) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (!SOURCES.contains(cmd.source())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "来源不合法：仅支持 DOCTOR_ADVICE/COURSE_FOLLOW/SYSTEM_AUTO/MANUAL");
        }
        if (cmd.reason() == null || cmd.reason().isBlank() || cmd.reason().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "召回事由必填且 ≤200 字");
        }
        String method = cmd.method() == null || cmd.method().isBlank() ? "PHONE" : cmd.method().trim();
        if (!METHODS.contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "提醒方式不合法：仅支持 PHONE/WECHAT/SMS/IN_STORE");
        }
        LocalDate dueDate = parseDate(cmd.dueDate(), "dueDate");
        if (dueDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dueDate 必填（yyyy-MM-dd）");
        }
        LocalDate lastVisit = parseDate(cmd.lastVisitDate(), "lastVisitDate");
        CustomerDirectoryClient.CustomerDirectory c = customerClient.requireCustomer(cmd.customerId());
        String actor = DataScope.currentActor();
        Recall r = new Recall();
        r.setRecallNo(bizNo.next("RC", like -> recallRepo.findTopByRecallNoLikeOrderByRecallNoDesc(like)
                .map(Recall::getRecallNo).orElse(null)));
        r.setCustomerId(c.customerId());
        r.setCustomerName(c.name() == null ? "" : c.name());
        r.setSource(cmd.source());
        r.setReason(cmd.reason().trim());
        r.setRelatedEmrNo(blank(cmd.relatedEmrNo()));
        r.setRelatedOrderNo(blank(cmd.relatedOrderNo()));
        r.setLastVisitDate(lastVisit);
        r.setDueDate(dueDate);
        r.setMethod(method);
        r.setNote(blank(cmd.note()));
        r.setStoreCode(c.storeCode());
        r.setCreatedBy(actor);
        r.setTimeline(appendTimeline(null, actor, "创建复诊提醒", r.getReason()));
        Recall saved = recallRepo.save(r);
        audit(saved.getRecallNo(), "CREATE",
                "{\"customerId\":\"" + saved.getCustomerId() + "\",\"source\":\"" + saved.getSource()
                        + "\",\"dueDate\":\"" + saved.getDueDate() + "\"}");
        return toView(saved);
    }

    /** 执行提醒：PENDING → NOTIFIED（落 notified_by/notified_at；method 可空覆盖）。 */
    public RecallView notify(String recallNo, NotifyCmd cmd) {
        Recall r = require(recallNo);
        transit(r, "NOTIFIED");
        if (cmd != null && cmd.method() != null && !cmd.method().isBlank()) {
            String m = cmd.method().trim();
            if (!METHODS.contains(m)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "提醒方式不合法：仅支持 PHONE/WECHAT/SMS/IN_STORE");
            }
            r.setMethod(m);
        }
        String actor = DataScope.currentActor();
        r.setNotifiedBy(actor);
        r.setNotifiedAt(OffsetDateTime.now());
        r.setTimeline(appendTimeline(r.getTimeline(), actor, "已发送提醒", "方式：" + r.getMethod()));
        Recall saved = save(r);
        audit(saved.getRecallNo(), "NOTIFY", "{\"method\":\"" + saved.getMethod() + "\"}");
        return toView(saved);
    }

    /** 登记客户确认：NOTIFIED → CONFIRMED（落 customerReply/confirmedDate）。 */
    public RecallView confirm(String recallNo, ConfirmCmd cmd) {
        Recall r = require(recallNo);
        transit(r, "CONFIRMED");
        String reply = cmd == null ? null : blank(cmd.reply());
        LocalDate confirmedDate = cmd == null ? null : parseDate(cmd.confirmedDate(), "confirmedDate");
        r.setCustomerReply(reply);
        r.setConfirmedDate(confirmedDate);
        String actor = DataScope.currentActor();
        r.setTimeline(appendTimeline(r.getTimeline(), actor, "客户确认复诊", reply));
        Recall saved = save(r);
        audit(saved.getRecallNo(), "CONFIRM", "{\"confirmedDate\":"
                + (confirmedDate == null ? "null" : "\"" + confirmedDate + "\"") + "}");
        return toView(saved);
    }

    /** 预约落地：NOTIFIED/CONFIRMED → BOOKED。 */
    public RecallView book(String recallNo, DetailCmd cmd) {
        Recall r = require(recallNo);
        transit(r, "BOOKED");
        String detail = cmd == null ? null : blank(cmd.detail());
        String actor = DataScope.currentActor();
        r.setTimeline(appendTimeline(r.getTimeline(), actor, "已生成预约", detail));
        Recall saved = save(r);
        audit(saved.getRecallNo(), "BOOK", "{}");
        return toView(saved);
    }

    /** 跳过：PENDING/NOTIFIED/CONFIRMED → SKIPPED（reason 必填落 skip_reason）。 */
    public RecallView skip(String recallNo, SkipCmd cmd) {
        Recall r = require(recallNo);
        String reason = cmd == null ? null : blank(cmd.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跳过原因必填");
        }
        if (reason.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跳过原因超长（>200 字）");
        }
        transit(r, "SKIPPED");
        r.setSkipReason(reason);
        String actor = DataScope.currentActor();
        r.setTimeline(appendTimeline(r.getTimeline(), actor, "已跳过", reason));
        Recall saved = save(r);
        audit(saved.getRecallNo(), "SKIP", "{\"reason\":\"" + escape(reason) + "\"}");
        return toView(saved);
    }

    /** 改期：仅 PENDING/NOTIFIED 可改；NOTIFIED→PENDING 回退并清通知信息（与 mock 对齐）。 */
    public RecallView reschedule(String recallNo, RescheduleCmd cmd) {
        Recall r = require(recallNo);
        LocalDate newDue = cmd == null ? null : parseDate(cmd.dueDate(), "dueDate");
        if (newDue == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dueDate 必填（yyyy-MM-dd）");
        }
        if (!"PENDING".equals(r.getStatus()) && !"NOTIFIED".equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前状态 " + r.getStatus() + " 不可改期（仅 PENDING/NOTIFIED 支持）");
        }
        String note = blank(cmd.note());
        r.setDueDate(newDue);
        if (note != null) {
            r.setNote(note);
        }
        String actor = DataScope.currentActor();
        if ("NOTIFIED".equals(r.getStatus())) {
            r.setStatus("PENDING");
            r.setNotifiedBy(null);
            r.setNotifiedAt(null);
        }
        r.setTimeline(appendTimeline(r.getTimeline(), actor, "改期重新提醒",
                "复诊日期调整为 " + newDue + (note == null ? "" : "；" + note)));
        Recall saved = save(r);
        audit(saved.getRecallNo(), "RESCHEDULE", "{\"dueDate\":\"" + newDue + "\"}");
        return toView(saved);
    }

    // ==================== 内部 ====================

    /** 状态机校验：非法转移 409 中文（服务端唯一权威）。 */
    private void transit(Recall r, String target) {
        Set<String> allowed = TRANSITIONS.getOrDefault(r.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "非法状态转移：" + r.getStatus() + " → " + target + "（允许：" + allowed + "）");
        }
        r.setStatus(target);
    }

    private Recall require(String recallNo) {
        return recallRepo.findByRecallNo(recallNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "召回单不存在：" + recallNo));
    }

    private Recall save(Recall r) {
        r.setUpdatedAt(OffsetDateTime.now());
        return recallRepo.save(r);
    }

    /** KPI 全量口径（与前端 mock computed 一致，业务时区 +8 锚今日）。 */
    private static KpiView kpi(List<Recall> all) {
        LocalDate today = LocalDate.now(BIZ_TZ);
        long overdue = all.stream()
                .filter(r -> "PENDING".equals(r.getStatus()) && r.getDueDate() != null
                        && r.getDueDate().isBefore(today))
                .count();
        long todayPending = all.stream()
                .filter(r -> "PENDING".equals(r.getStatus()) && today.equals(r.getDueDate()))
                .count();
        long upcoming = all.stream()
                .filter(r -> "PENDING".equals(r.getStatus()) && r.getDueDate() != null
                        && !r.getDueDate().isBefore(today) && !r.getDueDate().isAfter(today.plusDays(3)))
                .count();
        long notified = all.stream().filter(r -> "NOTIFIED".equals(r.getStatus())).count();
        long confirmed = all.stream().filter(r -> "CONFIRMED".equals(r.getStatus())).count();
        long booked = all.stream().filter(r -> "BOOKED".equals(r.getStatus())).count();
        long reached = notified + confirmed + booked;
        long conversionRate = reached == 0 ? 0 : Math.round((confirmed + booked) * 100.0 / reached);
        return new KpiView(overdue, todayPending, upcoming, conversionRate);
    }

    /** timeline append：解析既有 JSON 数组（空/非法→新数组）追加 {at,by,action,detail} 节点。 */
    private static String appendTimeline(String existing, String actor, String action, String detail) {
        ArrayNode arr;
        try {
            arr = existing == null || existing.isBlank()
                    ? MAPPER.createArrayNode()
                    : (ArrayNode) MAPPER.readTree(existing);
        } catch (Exception ex) {
            log.warn("召回 timeline JSON 解析失败，重建数组：{}", ex.getMessage());
            arr = MAPPER.createArrayNode();
        }
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("at", OffsetDateTime.now(BIZ_TZ).toString());
        entry.put("by", actor);
        entry.put("action", action);
        if (detail != null && !detail.isBlank()) {
            entry.put("detail", detail);
        }
        arr.add(entry);
        try {
            return MAPPER.writeValueAsString(arr);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static LocalDate parseDate(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 格式须为 yyyy-MM-dd：" + s);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean contains(String s, String kw) {
        return s != null && s.contains(kw);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void audit(String txnNo, String action, String payload) {
        audit.record("RECALL", txnNo, DataScope.currentActor(), action, payload);
    }

    private static RecallView toView(Recall r) {
        return new RecallView(r.getRecallNo(), r.getRecallNo(), r.getCustomerId(),
                r.getCustomerName() == null ? "" : r.getCustomerName(),
                r.getSource(), r.getReason(), r.getRelatedEmrNo(), r.getRelatedOrderNo(),
                r.getLastVisitDate() == null ? null : r.getLastVisitDate().toString(),
                r.getDueDate() == null ? null : r.getDueDate().toString(),
                r.getMethod(), r.getStatus(),
                r.getNotifiedBy(), r.getNotifiedAt() == null ? null : r.getNotifiedAt().toString(),
                r.getCustomerReply(),
                r.getConfirmedDate() == null ? null : r.getConfirmedDate().toString(),
                r.getSkipReason(), r.getNote(),
                r.getTimeline() == null ? "[]" : r.getTimeline(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().toString(),
                r.getRuleNo(), r.getStoreCode());
    }
}
