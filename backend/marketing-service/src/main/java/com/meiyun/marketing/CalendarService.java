package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日历排期写链路（P5-B88）：节点查询 / 排期查询 / 新建排期（落 SCHEDULED）。
 *
 * 校验链与前端 m5Calendar store mock 语义逐字一致（顺序、中文文案）：
 *   节点存在「节点不存在」→ 名称「请填写活动名称」→ 渠道「至少选择一个推送渠道」
 *   → 起止「开始时间不能晚于结束时间」。
 * 敏感词双道（D6）：前端 checkSensitive 预检保留，服务端创建时对 name/benefitDesc/copyText
 * 合并串再经 {@link ForbiddenWordService#check} 拦截，命中抛 400 中文错误。
 * 幂等：create 带 client_token 命中返回已有行不重复审计。
 * Job 流转（CalendarScheduleJob 日更驱动）：startOne 仅 SCHEDULED→RUNNING、
 * endOne 仅 SCHEDULED/RUNNING→ENDED，单条独立事务，状态未变返回 false 不审计。
 */
@Service
public class CalendarService {

    /** 推送渠道合法值：SMS 短信 / WECOM 企微 / WECHAT_MP 公众号。 */
    public static final Set<String> CHANNELS = Set.of("SMS", "WECOM", "WECHAT_MP");

    private final CalendarNodeRepository nodeRepo;
    private final CalendarScheduleRepository scheduleRepo;
    private final BizNoGenerator noGen;
    private final ForbiddenWordService forbiddenWordService;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CalendarService(CalendarNodeRepository nodeRepo, CalendarScheduleRepository scheduleRepo,
                           BizNoGenerator noGen, ForbiddenWordService forbiddenWordService,
                           AuditRecorder audit) {
        this.nodeRepo = nodeRepo;
        this.scheduleRepo = scheduleRepo;
        this.noGen = noGen;
        this.forbiddenWordService = forbiddenWordService;
        this.audit = audit;
    }

    // ==================== 查询 ====================

    public List<CalendarNode> listNodes() {
        return nodeRepo.findAllByOrderByNodeDateAsc();
    }

    public List<CalendarSchedule> listSchedules() {
        return scheduleRepo.findAllByOrderByCreatedAtDesc();
    }

    // ==================== 写动作 ====================

    /** 新建排期（落 SCHEDULED 待开始；estimatedRevenueCents 单位分，元→分 ×100 在前端适配层）。 */
    @Transactional
    public CalendarSchedule create(ScheduleCmd cmd) {
        validate(cmd);
        if (cmd.clientToken() != null && !cmd.clientToken().isBlank()) {
            CalendarSchedule existing = scheduleRepo.findByClientToken(cmd.clientToken().trim()).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        CalendarNode node = nodeRepo.findById(cmd.nodeId().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "节点不存在"));
        OffsetDateTime now = OffsetDateTime.now();
        CalendarSchedule s = new CalendarSchedule();
        s.setScheduleId(noGen.next("CS", like -> scheduleRepo
                .findTopByScheduleIdLikeOrderByScheduleIdDesc(like)
                .map(CalendarSchedule::getScheduleId).orElse(null)));
        s.setNodeId(node.getNodeId());
        s.setNodeDate(node.getNodeDate());
        s.setScheduleName(cmd.name().trim());
        s.setBenefitDesc(trim(cmd.benefitDesc()));
        s.setCouponIds(toJson(cmd.couponIds() == null ? List.of() : cmd.couponIds(), "关联券"));
        s.setPointsReward(cmd.pointsReward() == null ? 0 : cmd.pointsReward());
        s.setStartDate(cmd.startDate());
        s.setEndDate(cmd.endDate());
        s.setChannels(toJson(cmd.channels(), "推送渠道"));
        s.setCopyText(trim(cmd.copyText()));
        s.setStatus("SCHEDULED");
        s.setEstimatedRevenueCents(cmd.estimatedRevenueCents() == null ? 0L : cmd.estimatedRevenueCents());
        s.setStoreCode(cmd.storeCode() == null || cmd.storeCode().isBlank() ? null : cmd.storeCode().trim());
        s.setCreatedBy(DataScope.currentActor());
        s.setClientToken(cmd.clientToken() == null || cmd.clientToken().isBlank() ? null : cmd.clientToken().trim());
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        CalendarSchedule saved = scheduleRepo.save(s);
        audit("CREATE", saved.getScheduleId(), Map.of(
                "name", saved.getScheduleName(), "nodeId", saved.getNodeId(),
                "nodeDate", String.valueOf(saved.getNodeDate()), "status", saved.getStatus()));
        return saved;
    }

    /** Job 启动：仅 SCHEDULED → RUNNING；幂等——其他状态返回 false 不审计。 */
    @Transactional
    public boolean startOne(String scheduleId, LocalDate today) {
        CalendarSchedule s = mustGet(scheduleId);
        if (!"SCHEDULED".equals(s.getStatus())) {
            return false;
        }
        s.setStatus("RUNNING");
        s.setUpdatedAt(OffsetDateTime.now());
        scheduleRepo.save(s);
        audit("START", scheduleId, Map.of(
                "name", s.getScheduleName(), "to", "RUNNING", "today", String.valueOf(today)));
        return true;
    }

    /** Job 结束：仅 SCHEDULED/RUNNING → ENDED；幂等——其他状态返回 false 不审计。 */
    @Transactional
    public boolean endOne(String scheduleId, LocalDate today) {
        CalendarSchedule s = mustGet(scheduleId);
        if (!"SCHEDULED".equals(s.getStatus()) && !"RUNNING".equals(s.getStatus())) {
            return false;
        }
        s.setStatus("ENDED");
        s.setUpdatedAt(OffsetDateTime.now());
        scheduleRepo.save(s);
        audit("END", scheduleId, Map.of(
                "name", s.getScheduleName(), "from", s.getStatus(), "to", "ENDED",
                "today", String.valueOf(today)));
        return true;
    }

    // ==================== 内部方法 ====================

    private void validate(ScheduleCmd cmd) {
        if (cmd.nodeId() == null || cmd.nodeId().isBlank() || !nodeRepo.existsById(cmd.nodeId().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "节点不存在");
        }
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写活动名称");
        }
        if (cmd.name().trim().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动名称长度不可超过 64 字");
        }
        if (cmd.channels() == null || cmd.channels().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一个推送渠道");
        }
        if (!CHANNELS.containsAll(cmd.channels())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "推送渠道不合法（SMS/WECOM/WECHAT_MP）");
        }
        if (cmd.startDate() == null || cmd.endDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择活动起止日期");
        }
        if (cmd.startDate().isAfter(cmd.endDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开始时间不能晚于结束时间");
        }
        List<String> hits = new ArrayList<>(forbiddenWordService.check(String.join(" ",
                cmd.name() == null ? "" : cmd.name(),
                cmd.benefitDesc() == null ? "" : cmd.benefitDesc(),
                cmd.copyText() == null ? "" : cmd.copyText())));
        if (!hits.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文案命中违禁词：" + String.join("、", hits));
        }
    }

    private CalendarSchedule mustGet(String scheduleId) {
        return scheduleRepo.findById(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "排期不存在：" + scheduleId));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private String toJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "数据不合法");
        }
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("CALENDAR_SCHEDULE", txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("CALENDAR_SCHEDULE", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    /**
     * 新建排期命令；estimatedRevenueCents 单位分（前端适配层由元 ×100）；
     * storeCode 可空（NULL=全连锁，D2-A）；clientToken 为创建幂等令牌（可空）。
     */
    public record ScheduleCmd(
            String nodeId,
            String name,
            String benefitDesc,
            List<String> couponIds,
            Integer pointsReward,
            LocalDate startDate,
            LocalDate endDate,
            List<String> channels,
            String copyText,
            Long estimatedRevenueCents,
            String storeCode,
            String clientToken) {}
}
