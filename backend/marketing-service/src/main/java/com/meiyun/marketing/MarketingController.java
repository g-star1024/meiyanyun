package com.meiyun.marketing;

import com.meiyun.common.event.DomainEventPublisher;
import com.meiyun.common.ratelimit.RateLimiter;
import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * marketing-service（M5 营销）控制器。
 *
 * 写链路：券模板（创建/启用/停用/发放）与活动（创建/状态流转）全部落 Service，
 * 统一具备参数校验、状态机、幂等与全动作审计（audit_log jsonb）。
 *
 * 红线：
 * ① 触达每周每客户 ≤3 条（weekly_push_limit，发送前查近 7 天计数，超限 400）；
 * ② 四类违禁词发送前实时校验（A1-04 服务化：DB 词库 + Redis 缓存 + 管理端维护），命中即拦截；
 * ③ 核销链三段 842 = 正常 774 + 异常 9 + 待处理 59。
 */
@RestController
@RequestMapping("/api/marketing")
@RequirePerm("marketing:view")
public class MarketingController {

    private final CampaignService campaignService;
    private final CouponService couponService;
    private final MarketingStatsService statsService;
    private final ForbiddenWordService forbiddenWordService;
    private final PushRecordRepository pushRepo;
    private final CouponWriteoffChainRepository chainRepo;
    private final MarketingCfgRepository cfgRepo;
    private final RateLimiter rateLimiter;
    private final DomainEventPublisher events;

    private static final int PUSH_WINDOW_SECONDS = 7 * 24 * 3600; // 周频窗口
    private static final String PUSH_TOPIC = "meiyun.marketing.push-sent";
    /** 合法推送渠道码（与前端 PushChannel 契约一致；push_type 列 varchar(16)）。 */
    private static final java.util.Set<String> PUSH_TYPES = java.util.Set.of("SMS", "WECOM", "WECHAT_MP");
    private static final int PUSH_CONTENT_MAX = 256;

    public MarketingController(CampaignService campaignService, CouponService couponService,
                               MarketingStatsService statsService,
                               ForbiddenWordService forbiddenWordService,
                               PushRecordRepository pushRepo, CouponWriteoffChainRepository chainRepo,
                               MarketingCfgRepository cfgRepo, RateLimiter rateLimiter,
                               DomainEventPublisher events) {
        this.campaignService = campaignService;
        this.couponService = couponService;
        this.statsService = statsService;
        this.forbiddenWordService = forbiddenWordService;
        this.pushRepo = pushRepo;
        this.chainRepo = chainRepo;
        this.cfgRepo = cfgRepo;
        this.rateLimiter = rateLimiter;
        this.events = events;
    }

    // ==================== 配置 ====================

    @GetMapping("/config")
    public MarketingCfg config() {
        return cfgRepo.findById(1).orElseGet(MarketingCfg::new);
    }

    // ==================== M5-06 ROI 统计聚合 ====================

    /**
     * 发券量 / 核销量 / 活动转化统计汇总（只读，类级 marketing:view 权限覆盖）。
     * 金额单位「分」，核销率/达成率/ROI 为 0~1 / 倍数比率；空表全部返回 0 与空明细。
     */
    @GetMapping("/stats/overview")
    public Map<String, Object> statsOverview() {
        return statsService.overview();
    }

    // ==================== 活动 ====================

    @GetMapping("/campaigns")
    public List<Campaign> campaigns() {
        return campaignService.list();
    }

    @PostMapping("/campaign")
    @RequirePerm("marketing:create")
    public Campaign createCampaign(@RequestBody @Valid CampaignService.CampaignCmd cmd) {
        return campaignService.create(cmd);
    }

    /** 活动状态流转（草稿→待开始→进行中→已结束/取消），非法流转由 Service 抛 400 中文错误。 */
    @PostMapping("/campaigns/{id}/transit")
    @RequirePerm("marketing:edit")
    public Map<String, Object> transitCampaign(@PathVariable String id,
                                               @RequestBody CampaignService.TransitCmd cmd) {
        boolean changed = campaignService.transit(id, cmd == null ? null : cmd.to());
        return Map.of("changed", changed);
    }

    // ==================== 优惠券 ====================

    @GetMapping("/coupons")
    public List<CouponTemplate> coupons() {
        return couponService.list();
    }

    @PostMapping("/coupons")
    @RequirePerm("coupon:create")
    public CouponTemplate createCoupon(@RequestBody @Valid CouponService.CouponCmd cmd) {
        return couponService.create(cmd);
    }

    @PostMapping("/coupons/{id}/enable")
    @RequirePerm("coupon:edit")
    public Map<String, Object> enableCoupon(@PathVariable String id) {
        return Map.of("changed", couponService.enable(id));
    }

    @PostMapping("/coupons/{id}/disable")
    @RequirePerm("coupon:edit")
    public Map<String, Object> disableCoupon(@PathVariable String id) {
        return Map.of("changed", couponService.disable(id));
    }

    /** 发券（防超发）：库存发完返回 409，部分发放正常落库。 */
    @PostMapping("/coupons/{id}/grant")
    @RequirePerm("coupon:edit")
    public CouponGrant grantCoupon(@PathVariable String id, @RequestBody @Valid CouponService.GrantCmd cmd) {
        return couponService.grant(id, cmd);
    }

    @GetMapping("/coupon-grants")
    public List<CouponGrant> couponGrants() {
        return couponService.listGrants();
    }

    // ==================== 触达（周频限 + 违禁词） ====================

    /**
     * 发送触达：先违禁词校验，再周频限制（近 7 天 ≤ weekly_push_limit 条），双红线都过才落库。
     */
    @PostMapping("/push")
    @RequirePerm("push:create")
    @Transactional
    public PushRecord push(@RequestBody @Valid PushCmd cmd) {
        // 渠道白名单：push_type 列 varchar(16)，只收三渠道英文码，非法值给中文 400（不放行到落库超长报错）
        if (!PUSH_TYPES.contains(cmd.pushType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "推送渠道不合法：仅支持 短信(SMS)/企业微信(WECOM)/微信公众号(WECHAT_MP)");
        }
        if (cmd.content().length() > PUSH_CONTENT_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "推送内容过长：最多 " + PUSH_CONTENT_MAX + " 字，当前 " + cmd.content().length() + " 字");
        }
        // 红线②：违禁词校验（DB 词库 + 缓存，管理端可维护）
        List<String> hits = forbiddenWordService.check(cmd.content());
        if (!hits.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "营销合规拦截：命中违禁词 " + String.join("; ", hits));
        }
        // 红线①：周频限制（经 RateLimiter 抽象：DB 计数默认 / Redis 原子计数生产，配置切换）
        int limit = config().getWeeklyPushLimit() == null ? 3 : config().getWeeklyPushLimit();
        String rlKey = "push:customer:" + cmd.customerId();
        if (!rateLimiter.tryAcquire(rlKey, limit, PUSH_WINDOW_SECONDS)) {
            long sent = rateLimiter.currentCount(rlKey, PUSH_WINDOW_SECONDS);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "触达频控拦截：客户 " + cmd.customerId() + " 近 7 天已触达 " + sent + " 条，上限 " + limit);
        }
        PushRecord p = new PushRecord();
        p.setCustomerId(cmd.customerId());
        p.setPushType(cmd.pushType());
        p.setContent(cmd.content());
        p.setSentAt(OffsetDateTime.now());
        PushRecord saved = pushRepo.save(p);
        // 发布领域事件（默认日志实现；生产切 MQ，由 Outbox 兜底补偿）
        events.publish(PUSH_TOPIC, String.valueOf(saved.getPushId()),
                "{\"pushId\":\"" + saved.getPushId() + "\",\"customerId\":\"" + cmd.customerId()
                        + "\",\"pushType\":\"" + cmd.pushType() + "\"}");
        return saved;
    }

    /** 查询某客户近 7 天触达计数与剩余额度。 */
    @GetMapping("/push/quota/{customerId}")
    public Map<String, Object> quota(@PathVariable String customerId) {
        int limit = config().getWeeklyPushLimit() == null ? 3 : config().getWeeklyPushLimit();
        long sent = rateLimiter.currentCount("push:customer:" + customerId, PUSH_WINDOW_SECONDS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", customerId);
        m.put("sentLast7Days", sent);
        m.put("weeklyLimit", limit);
        m.put("remaining", Math.max(0, limit - sent));
        m.put("rateLimiter", rateLimiter.getClass().getSimpleName());
        return m;
    }

    @GetMapping("/push/history/{customerId}")
    public List<PushRecord> history(@PathVariable String customerId) {
        return pushRepo.findByCustomerIdOrderBySentAtDesc(customerId);
    }

    // ==================== 核销链 ====================

    /** 核销链三段（842 = 774 + 9 + 59）。 */
    @GetMapping("/writeoff-chain")
    public List<CouponWriteoffChain> writeoffChain() {
        return chainRepo.findAllByOrderByChainIdAsc();
    }

    // ==================== 违禁词库（A1-04 服务化：DB + 缓存 + 管理端维护） ====================

    /** 启用词按类别分组（前端文案预检与词库展示；DB 空表回落内置词库）。 */
    @GetMapping("/forbidden-words")
    public Map<String, List<String>> forbiddenWords() {
        return forbiddenWordService.categories();
    }

    /** 管理端全量词列表（含停用词，带 wordId 供启停/删除）。 */
    @GetMapping("/forbidden-words/list")
    public List<ForbiddenWordView> forbiddenWordList() {
        return forbiddenWordService.list();
    }

    /** 文案预检：发送前前端实时校验，返回命中词列表（不抛 400，供页面红字提示）。 */
    @PostMapping("/forbidden-words/check")
    public Map<String, Object> checkCopy(@RequestBody @Valid CheckCopyCmd cmd) {
        List<String> hits = forbiddenWordService.check(cmd.content());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passed", hits.isEmpty());
        m.put("hits", hits);
        return m;
    }

    /** 新增违禁词（幂等：同类别同词已存在直接返回；非法类别/空词 400 中文错误）。 */
    @PostMapping("/forbidden-words")
    @RequirePerm("marketing:edit")
    public ForbiddenWord createForbiddenWord(@RequestBody @Valid ForbiddenWordService.WordCmd cmd) {
        return forbiddenWordService.create(cmd);
    }

    /** 启用/停用违禁词（幂等 changed）。 */
    @PostMapping("/forbidden-words/{id}/toggle")
    @RequirePerm("marketing:edit")
    public Map<String, Object> toggleForbiddenWord(@PathVariable Long id,
                                                   @RequestBody ForbiddenWordService.ToggleCmd cmd) {
        boolean enabled = cmd == null || cmd.enabled() == null || cmd.enabled();
        return Map.of("changed", forbiddenWordService.toggle(id, enabled));
    }

    /** 删除违禁词（幂等：不存在返回 changed=false）。 */
    @PostMapping("/forbidden-words/{id}/delete")
    @RequirePerm("marketing:edit")
    public Map<String, Object> deleteForbiddenWord(@PathVariable Long id) {
        return Map.of("changed", forbiddenWordService.delete(id));
    }

    // ==================== 命令 DTO ====================

    public record PushCmd(
            @NotBlank String customerId, @NotBlank String pushType, @NotBlank String content) {}

    public record CheckCopyCmd(@NotBlank String content) {}
}
