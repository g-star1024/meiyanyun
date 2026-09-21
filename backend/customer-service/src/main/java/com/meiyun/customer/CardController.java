package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会员卡储值端点（B4 充值储值，对外收银/卡 360 页面）。
 *
 * <p>精确路径 {@code /cards/...} 优先于 {@code /{id}} 匹配，与 name-map/phone-map 同例，
 * 不会与客户详情 {@code GET /api/customer/{id}} 冲突。
 *
 * <p>写接口四件套：权限码 customer:card:recharge（收银/店长/超管）；卡不存在或越权统一 404
 * （不泄露卡是否存在）；金额/支付方式服务端白名单校验（充值禁用 balance）；操作人/门店取登录
 * 上下文，前端不可伪造；RC 单号服务端生成，重放 409；全审计落 CARD 链。
 */
@RestController
@RequestMapping("/api/customer/cards")
public class CardController {

    private final CardLedgerService ledgerService;
    private final MemberCardRepository cardRepo;

    public CardController(CardLedgerService ledgerService, MemberCardRepository cardRepo) {
        this.ledgerService = ledgerService;
        this.cardRepo = cardRepo;
    }

    /** 会员卡充值：POST /api/customer/cards/{cardNo}/recharge（amount 分；payMethod cash/card/wxpay/alipay）。 */
    @PostMapping("/{cardNo}/recharge")
    @RequirePerm("customer:card:recharge")
    public Map<String, Object> recharge(@PathVariable("cardNo") String cardNo,
                                        @RequestBody RechargeReq req) {
        MemberCard card = cardRepo.findById(cardNo)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(card.getStoreCode())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        long amount = req.amount() == null ? 0L : req.amount();
        long gift = req.giftAmount() == null ? 0L : req.giftAmount();
        String operator = DataScope.currentActor();
        CardLedger l = ledgerService.recharge(cardNo, amount, gift,
                req.payMethod() == null ? "" : req.payMethod().trim(),
                operator, card.getStoreCode());
        return Map.of(
                "ledgerId", l.getLedgerId(),
                "bizRef", l.getBizRef(),
                "balanceAfter", l.getBalanceAfter(),
                "giftAfter", l.getGiftAfter() == null ? 0L : l.getGiftAfter());
    }

    /** 卡储值流水：GET /api/customer/cards/{cardNo}/ledger（卡详情「储值流水」读模型，账龄正序）。 */
    @GetMapping("/{cardNo}/ledger")
    @RequirePerm("customer:view")
    public List<Map<String, Object>> ledger(@PathVariable("cardNo") String cardNo) {
        MemberCard card = cardRepo.findById(cardNo)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadStore(card.getStoreCode())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        return ledgerService.listLedger(cardNo).stream()
                .map(l -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("ledgerId", l.getLedgerId());
                    m.put("changeType", l.getChangeType());
                    m.put("amount", l.getAmount());
                    m.put("balanceAfter", l.getBalanceAfter());
                    m.put("giftAmount", l.getGiftAmount() == null ? 0L : l.getGiftAmount());
                    m.put("giftAfter", l.getGiftAfter() == null ? 0L : l.getGiftAfter());
                    m.put("bizRef", l.getBizRef() == null ? "" : l.getBizRef());
                    m.put("operator", l.getOperator() == null ? "" : l.getOperator());
                    m.put("createdAt", l.getCreatedAt() == null ? "" : l.getCreatedAt().toString());
                    return m;
                })
                .toList();
    }

    /** 充值入参：amount 本金分（&gt;0）、giftAmount 赠送金额分（≥0，可省=0）、payMethod（cash/card/wxpay/alipay，禁用 balance）；operator/storeCode 由服务端注入。 */
    public record RechargeReq(Long amount, Long giftAmount, String payMethod) {}

    /**
     * B83 卡1 L66 疗程跟踪聚合读：GET /api/customer/cards/course-track。
     * 全量返回当前门店 COURSE 卡（约 86 条在用，不分页），对齐前端 mock store asset.ts 的 TimesAsset。
     * 权限码 course:view（咨询师/医生/店长/超管），与 G2 核销记录读同权限。
     * 入参：storeCode 必填（前端从全局门店上下文传）；status 可选 ALL/ACTIVE/EXPIRING/FINISHED，默认 ALL。
     * trackStatus 由后端按 30 天阈值推导；daysLeft=（expiresAt-now）/1day，null 或负数返回 null。
     */
    @GetMapping("/course-track")
    @RequirePerm("course:view")
    public List<CourseTrackDTO> courseTrack(@RequestParam String storeCode,
                                            @RequestParam(defaultValue = "ALL") String status) {
        if (storeCode == null || storeCode.isBlank()) {
            throw new CustomerService.NotFound("门店编码不能为空");
        }
        // 数据域强制注入：SELF/STORE 本店、REGION 本区、GROUP 全量；越权统一空列表（不泄露门店是否存在）。
        if (!DataScope.canReadStore(storeCode)) {
            return List.of();
        }
        List<CourseTrackRow> rows = cardRepo.findCourseTrackRows(storeCode);
        Instant now = Instant.now();
        List<CourseTrackDTO> out = new ArrayList<>(rows.size());
        for (CourseTrackRow r : rows) {
            Instant expiresAt = r.getExpiresAt();
            Long daysLeft = computeDaysLeft(expiresAt, now);
            String trackStatus = deriveTrackStatus(r.getStatus(), expiresAt, daysLeft, now);
            // status 过滤：后端推导 trackStatus 后 filter（ALL 不过滤）。
            if (!"ALL".equalsIgnoreCase(status) && !status.equalsIgnoreCase(trackStatus)) {
                continue;
            }
            int total = r.getTotalTimes() == null ? 0 : r.getTotalTimes();
            int remain = r.getRemainTimes() == null ? 0 : r.getRemainTimes();
            out.add(new CourseTrackDTO(
                    r.getCardNo(),
                    r.getCustomerId(),
                    r.getCustomerName(),
                    CustomerService.maskPhone(r.getPhone(), false),
                    r.getCardItem(),
                    total,
                    remain,
                    total - remain,
                    r.getBalance() == null ? 0L : r.getBalance(),
                    r.getStatus(),
                    expiresAt,
                    daysLeft,
                    trackStatus));
        }
        return out;
    }

    /**
     * 剩余天数 = (expiresAt - now) / 1day；null 或已过期（负数）返回 null。
     * 对齐前端 EXPIRING_DAYS=30 阈值。
     */
    private Long computeDaysLeft(Instant expiresAt, Instant now) {
        if (expiresAt == null) return null;
        long seconds = expiresAt.getEpochSecond() - now.getEpochSecond();
        if (seconds <= 0) return null;
        return seconds / 86_400L;
    }

    /**
     * trackStatus 推导（30 天阈值）：
     * - 已退卡/退卡中 → FROZEN
     * - 已用完 / 在用且已过期未用完 → FINISHED
     * - 在用且 0 &lt; daysLeft ≤ 30 → EXPIRING
     * - 在用且 expiresAt 为空或 daysLeft &gt; 30 → ACTIVE
     */
    private String deriveTrackStatus(String cardStatus, Instant expiresAt, Long daysLeft, Instant now) {
        if ("已退卡".equals(cardStatus) || "退卡中".equals(cardStatus)) {
            return "FROZEN";
        }
        if ("已用完".equals(cardStatus)) {
            return "FINISHED";
        }
        if ("在用".equals(cardStatus)) {
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                // 已过期未用完，按已用完处理。
                return "FINISHED";
            }
            if (daysLeft != null && daysLeft > 0 && daysLeft <= 30) {
                return "EXPIRING";
            }
            return "ACTIVE";
        }
        // 未知状态兜底为 FROZEN，避免误判为 ACTIVE 暴露未跟踪卡。
        return "FROZEN";
    }
}
