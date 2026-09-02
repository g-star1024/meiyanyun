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
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * marketing-service（M5 营销）控制器。
 *
 * 红线：
 * ① 触达每周每客户 ≤3 条（weekly_push_limit，发送前查近 7 天计数，超限 400）；
 * ② 四类违禁词发送前实时校验，命中即拦截（绝对化用语/医疗承诺/虚假宣传/低俗诱导）；
 * ③ 核销链三段 842 = 正常 774 + 异常 9 + 待处理 59。
 */
@RestController
@RequestMapping("/api/marketing")
@RequirePerm("marketing:view")
public class MarketingController {

    private final CampaignRepository campaignRepo;
    private final CouponTemplateRepository couponRepo;
    private final PushRecordRepository pushRepo;
    private final CouponWriteoffChainRepository chainRepo;
    private final MarketingCfgRepository cfgRepo;
    private final RateLimiter rateLimiter;
    private final DomainEventPublisher events;
    private final AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000);

    private static final int PUSH_WINDOW_SECONDS = 7 * 24 * 3600; // 周频窗口
    private static final String PUSH_TOPIC = "meiyun.marketing.push-sent";

    public MarketingController(CampaignRepository campaignRepo, CouponTemplateRepository couponRepo,
                               PushRecordRepository pushRepo, CouponWriteoffChainRepository chainRepo,
                               MarketingCfgRepository cfgRepo, RateLimiter rateLimiter,
                               DomainEventPublisher events) {
        this.campaignRepo = campaignRepo;
        this.couponRepo = couponRepo;
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

    // ==================== 活动 / 券 ====================

    @GetMapping("/campaigns")
    public List<Campaign> campaigns() {
        return campaignRepo.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/campaign")
    @RequirePerm("marketing:create")
    public Campaign createCampaign(@RequestBody @Valid CampaignCmd cmd) {
        Campaign c = new Campaign();
        c.setCampaignId(nextNo("CP"));
        c.setCampaignName(cmd.name());
        c.setCampaignType(cmd.type());
        c.setStatus("草稿");
        c.setStartDate(cmd.startDate());
        c.setEndDate(cmd.endDate());
        c.setBudget(cmd.budget() == null ? 0L : cmd.budget());
        c.setCreatedAt(OffsetDateTime.now());
        return campaignRepo.save(c);
    }

    @GetMapping("/coupons")
    public List<CouponTemplate> coupons() {
        return couponRepo.findAllByOrderByCreatedAtDesc();
    }

    // ==================== 触达（周频限 + 违禁词） ====================

    /**
     * 发送触达：先违禁词校验，再周频限制（近 7 天 ≤ weekly_push_limit 条），双红线都过才落库。
     */
    @PostMapping("/push")
    @RequirePerm("push:create")
    @Transactional
    public PushRecord push(@RequestBody @Valid PushCmd cmd) {
        // 红线②：违禁词校验
        List<String> hits = ForbiddenWords.check(cmd.content());
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

    // ==================== 违禁词库 ====================

    @GetMapping("/forbidden-words")
    public Map<String, List<String>> forbiddenWords() {
        return ForbiddenWords.categories();
    }

    // ==================== 内部方法 ====================

    private String nextNo(String prefix) {
        long n = seq.incrementAndGet() % 1_000_000;
        return prefix + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", n);
    }

    // ==================== 命令 DTO ====================

    public record CampaignCmd(
            @NotBlank String name, @NotBlank String type,
            java.time.LocalDate startDate, java.time.LocalDate endDate, Long budget) {}

    public record PushCmd(
            @NotBlank String customerId, @NotBlank String pushType, @NotBlank String content) {}
}
