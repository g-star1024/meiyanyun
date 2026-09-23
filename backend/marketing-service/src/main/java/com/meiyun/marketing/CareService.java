package com.meiyun.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 关怀任务服务（P5-B90，/m3-care 切真，DESIGN §3 Care 六端点）。
 *
 * <p>列表+KPI 同响应（KPI 四键口径与前端 mock computed 全量口径一致，不受 status 过滤影响）；
 * 创建经 {@link CustomerDirectoryClient#requireCustomer} 硬校验回填姓名/门店（合规场景不降级），
 * content 经 {@link ForbiddenWordService#check} 违禁词校验；
 * send 渠道分流：SMS→PushService SMS、WECHAT→PushService WECOM 映射（PUSH_TYPES 无 WECHAT），
 * PHONE 仅登记 sent_at；consent 撤回/未授权/频控等 400 拦截捕获转 SendResult(skipped=true, reason)
 * 不落 record 不改状态（DESIGN §3「撤回同意→返回 skipped 原因不落 record」）。
 *
 * <p>无类级/方法级 @Transactional（沿 AutoGrantService 范式）：PushService.send 内层事务抛异常
 * 若被外层事务方法捕获会触发 rollback-only 污染；单实体落库由 repository 事务兜底。
 */
@Service
public class CareService {

    private static final Logger log = LoggerFactory.getLogger(CareService.class);
    private static final ZoneOffset BIZ_TZ = ZoneOffset.of("+08:00");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> CARE_TYPES = Set.of("BIRTHDAY", "HOLIDAY", "REPURCHASE", "REACTIVATE");
    private static final Set<String> CARE_CHANNELS = Set.of("SMS", "WECHAT", "PHONE");
    private static final String DEFAULT_CONTENT = "尊敬的客户，美研云为您送上专属关怀，欢迎到店体验。";

    private final CareTaskRepository careRepo;
    private final CustomerDirectoryClient customerClient;
    private final PushService pushService;
    private final ForbiddenWordService forbiddenWordService;
    private final MarketingCfgService cfgService;
    private final BizNoGenerator bizNo;
    private final AuditRecorder audit;

    public CareService(CareTaskRepository careRepo, CustomerDirectoryClient customerClient,
                       PushService pushService, ForbiddenWordService forbiddenWordService,
                       MarketingCfgService cfgService, BizNoGenerator bizNo, AuditRecorder audit) {
        this.careRepo = careRepo;
        this.customerClient = customerClient;
        this.pushService = pushService;
        this.forbiddenWordService = forbiddenWordService;
        this.cfgService = cfgService;
        this.bizNo = bizNo;
        this.audit = audit;
    }

    /** 任务视图（字段名对齐前端 care.ts CareTask 契约；id=care_no）。 */
    public record CareView(String id, String customerId, String customerName, String customerLevel,
                           String type, String channel, String templateName, String templateContent,
                           String scheduledAt, String status, String sentAt, boolean reached,
                           boolean replied, boolean convertedBooking, String assignee,
                           String ruleNo, String storeCode) {}

    /** KPI 四键（DESIGN §3：pendingThisMonth/sent/reachRate/converted）。 */
    public record KpiView(long pendingThisMonth, long sent, long reachRate, long converted) {}

    public record ListResp(List<CareView> tasks, KpiView kpi) {}

    /** 创建命令（content 可空=默认文案；planDate=yyyy-MM-dd）。 */
    public record CreateCmd(String customerId, String type, String channel, String content, String planDate) {}

    /** send 结果：skipped=true 表示合规/故障拦截未发送（reason 中文原因，任务保持 PENDING）。 */
    public record SendResult(boolean skipped, String reason, CareView task) {}

    public record FlagCmd(Boolean reached, Boolean converted) {}

    public record TemplateView(String id, String name, String channel, String content) {}

    /** 列表（status=PENDING/SENT 语义过滤，month=yyyy-MM 过滤 planDate，storeCode 精确）＋全量 KPI。 */
    public ListResp list(String status, String month, String storeCode) {
        LocalDate monthStart = null;
        LocalDate monthEnd = null;
        if (month != null && !month.isBlank()) {
            try {
                monthStart = LocalDate.parse(month.trim() + "-01");
                monthEnd = monthStart.plusMonths(1);
            } catch (DateTimeParseException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month 格式须为 yyyy-MM：" + month);
            }
        }
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (st != null && !Set.of("PENDING", "SENT", "REACHED", "ALL").contains(st)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 不合法：" + status);
        }
        String sc = storeCode == null || storeCode.isBlank() ? null : storeCode.trim();
        List<CareTask> all = careRepo.findAllByOrderByCreatedAtDesc();
        final LocalDate ms = monthStart;
        final LocalDate me = monthEnd;
        List<CareView> tasks = all.stream()
                .filter(t -> st == null || "ALL".equals(st)
                        || ("SENT".equals(st) ? !"PENDING".equals(t.getStatus()) : st.equals(t.getStatus())))
                .filter(t -> ms == null || (t.getPlanDate() != null
                        && !t.getPlanDate().isBefore(ms) && t.getPlanDate().isBefore(me)))
                .filter(t -> sc == null || sc.equals(t.getStoreCode()))
                .map(CareService::toView)
                .toList();
        return new ListResp(tasks, kpi(all));
    }

    /** 创建关怀任务（PENDING；客户硬校验回填姓名/门店；content 违禁词校验）。 */
    public CareView create(CreateCmd cmd) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (!CARE_TYPES.contains(cmd.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "关怀类型不合法：仅支持 BIRTHDAY/HOLIDAY/REPURCHASE/REACTIVATE");
        }
        if (!CARE_CHANNELS.contains(cmd.channel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道不合法：仅支持 SMS/WECHAT/PHONE");
        }
        LocalDate planDate;
        try {
            planDate = LocalDate.parse(cmd.planDate() == null ? "" : cmd.planDate().trim());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "planDate 格式须为 yyyy-MM-dd");
        }
        CustomerDirectoryClient.CustomerDirectory c = customerClient.requireCustomer(cmd.customerId());
        String content = cmd.content() == null || cmd.content().isBlank() ? DEFAULT_CONTENT : cmd.content().trim();
        if (content.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关怀内容超长（>500 字）");
        }
        List<String> hits = forbiddenWordService.check(content);
        if (!hits.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "营销合规拦截：命中违禁词 " + String.join("; ", hits));
        }
        CareTask t = new CareTask();
        t.setCareNo(bizNo.next("CARE", like -> careRepo.findTopByCareNoLikeOrderByCareNoDesc(like)
                .map(CareTask::getCareNo).orElse(null)));
        t.setCustomerId(c.customerId());
        t.setCustomerName(c.name() == null ? "" : c.name());
        t.setType(cmd.type());
        t.setChannel(cmd.channel());
        t.setContent(content);
        t.setPlanDate(planDate);
        t.setStoreCode(c.storeCode());
        t.setCreatedBy(DataScope.currentActor());
        CareTask saved = careRepo.save(t);
        audit(saved.getCareNo(), "CREATE",
                "{\"customerId\":\"" + saved.getCustomerId() + "\",\"type\":\"" + saved.getType()
                        + "\",\"channel\":\"" + saved.getChannel() + "\"}");
        return toView(saved);
    }

    /**
     * 发送：SMS/WECHAT 经 PushService（consent 门控＋违禁词＋频控＋幂等，WECHAT→WECOM 映射），
     * PHONE 仅登记 sent_at。PushService 400 拦截（撤回/未授权/频控/违禁词）捕获转 skipped，
     * 不落 record 不改状态；域故障 502 同样转 skipped（任务保持 PENDING 可稍后重试）。
     */
    public SendResult send(String careNo) {
        CareTask t = careRepo.findByCareNo(careNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "关怀任务不存在：" + careNo));
        if (!"PENDING".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅待发送任务可执行发送（当前状态 " + t.getStatus() + "）");
        }
        if ("PHONE".equals(t.getChannel())) {
            t.setStatus("SENT");
            t.setSentAt(OffsetDateTime.now());
            t.setUpdatedAt(OffsetDateTime.now());
            CareTask saved = careRepo.save(t);
            audit(saved.getCareNo(), "SEND", "{\"channel\":\"PHONE\"}");
            return new SendResult(false, null, toView(saved));
        }
        String pushType = "SMS".equals(t.getChannel()) ? "SMS" : "WECOM";
        try {
            pushService.send(new MarketingController.PushCmd(t.getCustomerId(), pushType, t.getContent()));
        } catch (ResponseStatusException ex) {
            String reason = ex.getReason() == null ? "发送被拦截" : ex.getReason();
            log.info("关怀发送跳过 careNo={}：{}", t.getCareNo(), reason);
            return new SendResult(true, reason, toView(t));
        }
        t.setStatus("SENT");
        t.setSentAt(OffsetDateTime.now());
        t.setUpdatedAt(OffsetDateTime.now());
        CareTask saved = careRepo.save(t);
        audit(saved.getCareNo(), "SEND", "{\"channel\":\"" + saved.getChannel() + "\"}");
        return new SendResult(false, null, toView(saved));
    }

    /** 标记/取消触达（须已发送；true→REACHED 落 reachedAt，false→仅清 reached 标记）。 */
    public CareView reach(String careNo, boolean reached) {
        CareTask t = careRepo.findByCareNo(careNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "关怀任务不存在：" + careNo));
        if ("PENDING".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未发送任务不可标记触达");
        }
        t.setReached(reached);
        if (reached) {
            t.setStatus("REACHED");
            t.setReachedAt(OffsetDateTime.now());
        }
        t.setUpdatedAt(OffsetDateTime.now());
        CareTask saved = careRepo.save(t);
        audit(saved.getCareNo(), "REACH", "{\"reached\":" + reached + "}");
        return toView(saved);
    }

    /** 登记/取消转化预约（带来预约计数）。 */
    public CareView convert(String careNo, boolean converted) {
        CareTask t = careRepo.findByCareNo(careNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "关怀任务不存在：" + careNo));
        t.setConvertedBooking(converted);
        t.setUpdatedAt(OffsetDateTime.now());
        CareTask saved = careRepo.save(t);
        audit(saved.getCareNo(), "CONVERT", "{\"converted\":" + converted + "}");
        return toView(saved);
    }

    /** 关怀模板（marketing_cfg.care_templates JSON，由 CareTemplateDataInitializer 播种 5 条）。 */
    @SuppressWarnings("unchecked")
    public List<TemplateView> templates() {
        String json = cfgService.get().getCareTemplates();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> rows = MAPPER.readValue(json, List.class);
            return rows.stream()
                    .map(m -> new TemplateView(m.get("id"), m.get("name"), m.get("channel"), m.get("content")))
                    .toList();
        } catch (Exception ex) {
            log.warn("关怀模板 JSON 解析失败，返回空清单：{}", ex.getMessage());
            return List.of();
        }
    }

    // ==================== 内部 ====================

    /** KPI 全量口径（与前端 mock computed 一致：sent=非 PENDING 计数，reachRate=reached/sent 百分数整数）。 */
    private static KpiView kpi(List<CareTask> all) {
        LocalDate now = LocalDate.now(BIZ_TZ);
        long pendingThisMonth = all.stream()
                .filter(t -> "PENDING".equals(t.getStatus()) && t.getPlanDate() != null
                        && t.getPlanDate().getYear() == now.getYear()
                        && t.getPlanDate().getMonth() == now.getMonth())
                .count();
        long sent = all.stream().filter(t -> !"PENDING".equals(t.getStatus())).count();
        long reached = all.stream().filter(t -> Boolean.TRUE.equals(t.getReached())).count();
        long converted = all.stream().filter(t -> Boolean.TRUE.equals(t.getConvertedBooking())).count();
        long reachRate = sent == 0 ? 0 : Math.round(reached * 100.0 / sent);
        return new KpiView(pendingThisMonth, sent, reachRate, converted);
    }

    private void audit(String txnNo, String action, String payload) {
        audit.record("CARE", txnNo, DataScope.currentActor(), action, payload);
    }

    private static CareView toView(CareTask t) {
        boolean converted = Boolean.TRUE.equals(t.getConvertedBooking());
        return new CareView(t.getCareNo(), t.getCustomerId(),
                t.getCustomerName() == null ? "" : t.getCustomerName(), "",
                t.getType(), t.getChannel(), "自定义内容", t.getContent() == null ? "" : t.getContent(),
                t.getPlanDate() == null ? null : t.getPlanDate().toString(), t.getStatus(),
                t.getSentAt() == null ? null : t.getSentAt().toString(),
                Boolean.TRUE.equals(t.getReached()), converted, converted,
                t.getCreatedBy() == null ? "" : t.getCreatedBy(), t.getRuleNo(), t.getStoreCode());
    }
}
