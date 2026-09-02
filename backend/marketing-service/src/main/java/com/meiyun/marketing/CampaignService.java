package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 营销活动写链路：创建（草稿）/ 状态流转。
 *
 * 状态机（英文码落库，前端经 label 映射中文）：
 * DRAFT 草稿 → SCHEDULED 待开始 → RUNNING 进行中 → ENDED 已结束；CANCELLED 已取消为旁路终态。
 * 合法流转与前端 m1Marketing store 的 TRANSITIONS 完全一致：
 *   DRAFT     → [SCHEDULED, CANCELLED]
 *   SCHEDULED → [RUNNING, DRAFT, CANCELLED]
 *   RUNNING   → [ENDED, CANCELLED]
 *   ENDED / CANCELLED 为终态。
 * 金额口径：入参/落库均为「分」；channels 为渠道中文名 JSON 数组文本。
 */
@Service
public class CampaignService {

    public static final Set<String> TYPES =
            Set.of("FULL_REDUCE", "DISCOUNT", "COUPON_PACK", "GIFT", "NEWBIE", "VIP_DAY");

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("SCHEDULED", "CANCELLED"),
            "SCHEDULED", Set.of("RUNNING", "DRAFT", "CANCELLED"),
            "RUNNING", Set.of("ENDED", "CANCELLED"),
            "ENDED", Set.of(),
            "CANCELLED", Set.of());

    private final CampaignRepository campaignRepo;
    private final BizNoGenerator noGen;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CampaignService(CampaignRepository campaignRepo, BizNoGenerator noGen, AuditRecorder audit) {
        this.campaignRepo = campaignRepo;
        this.noGen = noGen;
        this.audit = audit;
    }

    // ==================== 查询 ====================

    public List<Campaign> list() {
        return campaignRepo.findAllByOrderByCreatedAtDesc();
    }

    // ==================== 写动作 ====================

    /** 创建活动（落 DRAFT 草稿，spent/actualAmount/newCustomers 初始为 0）。 */
    @Transactional
    public Campaign create(CampaignCmd cmd) {
        validate(cmd);
        Campaign c = new Campaign();
        c.setCampaignId(noGen.next("CP", like -> campaignRepo
                .findTopByCampaignIdLikeOrderByCampaignIdDesc(like).map(Campaign::getCampaignId).orElse(null)));
        c.setCampaignName(cmd.name().trim());
        c.setCampaignType(cmd.type());
        c.setStatus("DRAFT");
        c.setChannels(toJsonArray(cmd.channels()));
        c.setStartDate(cmd.startDate());
        c.setEndDate(cmd.endDate());
        c.setBudget(cmd.budget() == null ? 0L : cmd.budget());
        c.setSpent(0L);
        c.setTargetAmount(cmd.targetAmount() == null ? 0L : cmd.targetAmount());
        c.setActualAmount(0L);
        c.setNewCustomers(0);
        c.setStoreScope(cmd.storeScope() == null || cmd.storeScope().isBlank() ? "全部门店" : cmd.storeScope().trim());
        c.setOwner(cmd.owner() == null || cmd.owner().isBlank() ? "系统" : cmd.owner().trim());
        c.setRemark(cmd.remark());
        c.setCreatedAt(OffsetDateTime.now());
        Campaign saved = campaignRepo.save(c);
        audit("CREATE", saved.getCampaignId(), Map.of(
                "name", saved.getCampaignName(), "type", saved.getCampaignType(),
                "budget", saved.getBudget(), "status", saved.getStatus()));
        return saved;
    }

    /**
     * 状态流转。返回是否实际发生变更：
     * 幂等——当前状态已是目标状态时返回 false 不审计；非法流转抛 400 中文错误。
     */
    @Transactional
    public boolean transit(String campaignId, String to) {
        Campaign c = mustGet(campaignId);
        if (to == null || !TRANSITIONS.containsKey(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标状态不合法：" + to);
        }
        String from = c.getStatus();
        if (from.equals(to)) {
            return false;
        }
        if (!TRANSITIONS.get(from).contains(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前状态为「" + statusLabel(from) + "」的活动不可流转为「" + statusLabel(to) + "」");
        }
        c.setStatus(to);
        campaignRepo.save(c);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", c.getCampaignName());
        payload.put("from", from);
        payload.put("to", to);
        audit("TRANSIT", campaignId, payload);
        return true;
    }

    // ==================== 内部方法 ====================

    private void validate(CampaignCmd cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动名称不可为空");
        }
        if (cmd.type() == null || !TYPES.contains(cmd.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "活动类型不合法（FULL_REDUCE/DISCOUNT/COUPON_PACK/GIFT/NEWBIE/VIP_DAY）");
        }
        if (cmd.channels() == null || cmd.channels().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个投放渠道");
        }
        if (cmd.startDate() != null && cmd.endDate() != null && cmd.endDate().isBefore(cmd.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动结束日期不可早于开始日期");
        }
        if (cmd.budget() != null && cmd.budget() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动预算不可为负");
        }
        if (cmd.targetAmount() != null && cmd.targetAmount() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标成交额不可为负");
        }
    }

    private String toJsonArray(List<String> channels) {
        try {
            return objectMapper.writeValueAsString(channels);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "投放渠道数据不合法");
        }
    }

    private Campaign mustGet(String campaignId) {
        return campaignRepo.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在：" + campaignId));
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("CAMPAIGN", txnNo, DataScope.currentActor(), action, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("CAMPAIGN", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "SCHEDULED" -> "待开始";
            case "RUNNING" -> "进行中";
            case "ENDED" -> "已结束";
            case "CANCELLED" -> "已取消";
            default -> status;
        };
    }

    // ==================== 命令 DTO ====================

    /** 创建活动命令。金额单位「分」；channels 为渠道中文名列表。 */
    public record CampaignCmd(
            String name,
            String type,
            List<String> channels,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            Long budget,
            Long targetAmount,
            String storeScope,
            String owner,
            String remark) {}

    /** 状态流转命令。 */
    public record TransitCmd(String to) {}
}
