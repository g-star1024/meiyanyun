package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 营销全局配置写链路（M5-15 营销设置）。
 *
 * <p>写接口四件套：① 参数校验（周频 1~3 硬约束、HH:mm 时段格式、审批层级 1|2、
 * 推送渠道⊆三渠道白名单、投放渠道⊆六渠道白名单、大额阈值&gt;0）；② 幂等（十个设置字段
 * 全部未变返回 changed=false，不审计）；③ 全动作审计（bizType=MARKETING_CFG，payload JSON）；
 * ④ 中文错误。
 *
 * <p>金额口径：大额券阈值 bigint 存「分」，前端活规格用「元」；渠道列存 JSON 数组文本。
 */
@Service
public class MarketingCfgService {

    /** 周频硬约束：上限锁定 3，不得通过设置放宽。 */
    public static final int WEEKLY_HARD_LIMIT = 3;
    public static final int WEEKLY_MIN = 1;

    /** 合法推送渠道码（与 PushChannel 契约一致）。 */
    public static final List<String> PUSH_CHANNELS = List.of("SMS", "WECOM", "WECHAT_MP");

    /** 合法投放渠道（与前端 AD_CHANNELS 六渠道契约一致，库内中文直存）。 */
    public static final List<String> AD_CHANNELS =
            List.of("抖音", "小红书", "美团", "大众点评", "微信私域", "视频号");

    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private final MarketingCfgRepository cfgRepo;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MarketingCfgService(MarketingCfgRepository cfgRepo, AuditRecorder audit) {
        this.cfgRepo = cfgRepo;
        this.audit = audit;
    }

    // ==================== 查询 ====================

    /** 读取单行配置（cfgId=1）；不存在返回空对象（Controller 兜底）。 */
    public MarketingCfg get() {
        return cfgRepo.findById(1).orElseGet(MarketingCfg::new);
    }

    // ==================== 写动作 ====================

    /**
     * 保存营销设置（upsert cfgId=1）。
     * 只更新 M5-15 十个设置字段；老带新奖励/佣金比例等既有字段不动。
     * 全字段与现值一致时返回 changed=false 且不审计。
     */
    @Transactional
    public Map<String, Object> save(ConfigCmd cmd) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提交内容为空");
        }
        validate(cmd);

        MarketingCfg cfg = cfgRepo.findById(1).orElseGet(MarketingCfg::new);
        if (cfg.getCfgId() == null) {
            cfg.setCfgId(1);
        }

        List<String> pushChannels = cmd.defaultPushChannels() == null ? List.of() : cmd.defaultPushChannels();
        List<String> adChannels = cmd.defaultAdChannels() == null ? List.of() : cmd.defaultAdChannels();

        boolean changed = !eq(cfg.getWeeklyPushLimit(), cmd.weeklyLimit())
                || !eq(cfg.getQuietHoursEnabled(), cmd.quietHoursEnabled())
                || !eq(cfg.getQuietStart(), cmd.quietStart())
                || !eq(cfg.getQuietEnd(), cmd.quietEnd())
                || !eq(cfg.getHolidayExempt(), cmd.holidayExempt())
                || !eq(cfg.getLargeCouponThresholdFen(), cmd.largeCouponThresholdFen())
                || !eq(cfg.getPushRequiresApproval(), cmd.pushRequiresApproval())
                || !eq(cfg.getApprovalLevel(), cmd.approvalLevel())
                || !channelsEqual(cfg.getDefaultPushChannels(), pushChannels)
                || !channelsEqual(cfg.getDefaultAdChannels(), adChannels);

        Map<String, Object> result = new LinkedHashMap<>();
        if (!changed) {
            result.put("changed", false);
            return result;
        }

        cfg.setWeeklyPushLimit(cmd.weeklyLimit());
        cfg.setQuietHoursEnabled(cmd.quietHoursEnabled());
        cfg.setQuietStart(cmd.quietStart());
        cfg.setQuietEnd(cmd.quietEnd());
        cfg.setHolidayExempt(cmd.holidayExempt());
        cfg.setLargeCouponThresholdFen(cmd.largeCouponThresholdFen());
        cfg.setPushRequiresApproval(cmd.pushRequiresApproval());
        cfg.setApprovalLevel(cmd.approvalLevel());
        cfg.setDefaultPushChannels(toJson(pushChannels));
        cfg.setDefaultAdChannels(toJson(adChannels));
        cfgRepo.save(cfg);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("weeklyLimit", cmd.weeklyLimit());
        payload.put("quietHoursEnabled", cmd.quietHoursEnabled());
        payload.put("quietStart", cmd.quietStart());
        payload.put("quietEnd", cmd.quietEnd());
        payload.put("holidayExempt", cmd.holidayExempt());
        payload.put("largeCouponThresholdFen", cmd.largeCouponThresholdFen());
        payload.put("pushRequiresApproval", cmd.pushRequiresApproval());
        payload.put("approvalLevel", cmd.approvalLevel());
        payload.put("defaultPushChannels", pushChannels);
        payload.put("defaultAdChannels", adChannels);
        audit("MARKETING_CFG", "SAVE", "CFG-1", payload);

        result.put("changed", true);
        return result;
    }

    // ==================== 校验 ====================

    private void validate(ConfigCmd cmd) {
        if (cmd.weeklyLimit() == null || cmd.weeklyLimit() < WEEKLY_MIN
                || cmd.weeklyLimit() > WEEKLY_HARD_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "周频上限不合法：取值范围 1~" + WEEKLY_HARD_LIMIT + " 条/周/客户（硬约束不得放宽）");
        }
        if (cmd.quietHoursEnabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定免打扰时段开关");
        }
        if (cmd.quietStart() == null || !HHMM.matcher(cmd.quietStart()).matches()
                || cmd.quietEnd() == null || !HHMM.matcher(cmd.quietEnd()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "免打扰时段格式不合法，应为 HH:mm（如 21:00）");
        }
        if (cmd.holidayExempt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定节日豁免开关");
        }
        if (cmd.largeCouponThresholdFen() == null || cmd.largeCouponThresholdFen() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "大额券审批阈值须为正数（单位：分）");
        }
        if (cmd.pushRequiresApproval() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定推送是否需审批");
        }
        if (cmd.approvalLevel() == null || (cmd.approvalLevel() != 1 && cmd.approvalLevel() != 2)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批层级不合法：仅支持 1=单级审批 或 2=两级审批");
        }
        List<String> push = cmd.defaultPushChannels();
        if (push == null || push.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个默认推送渠道");
        }
        for (String ch : push) {
            if (!PUSH_CHANNELS.contains(ch)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "推送渠道不合法：仅支持 短信(SMS)/企业微信(WECOM)/微信公众号(WECHAT_MP)");
            }
        }
        List<String> ads = cmd.defaultAdChannels();
        if (ads == null || ads.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一个默认投放渠道");
        }
        for (String ch : ads) {
            if (!AD_CHANNELS.contains(ch)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "投放渠道不合法：仅支持 " + String.join("、", AD_CHANNELS));
            }
        }
    }

    // ==================== 内部方法 ====================

    private boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 渠道列以 JSON 数组文本存储；比较时反序列化为有序列表，null/空串视为空列表。 */
    private boolean channelsEqual(String stored, List<String> expected) {
        List<String> storedList = parseChannels(stored);
        if (storedList.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < storedList.size(); i++) {
            if (!storedList.get(i).equals(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<String> parseChannels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String toJson(List<String> channels) {
        try {
            return objectMapper.writeValueAsString(channels);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private void audit(String bizType, String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record(bizType, txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record(bizType, txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    /**
     * 营销设置保存命令。
     * 金额口径：largeCouponThresholdFen bigint 存「分」（前端元 ×100）。
     */
    public record ConfigCmd(
            Integer weeklyLimit,
            Boolean quietHoursEnabled,
            String quietStart,
            String quietEnd,
            Boolean holidayExempt,
            Long largeCouponThresholdFen,
            Boolean pushRequiresApproval,
            Integer approvalLevel,
            List<String> defaultPushChannels,
            List<String> defaultAdChannels) {}
}
