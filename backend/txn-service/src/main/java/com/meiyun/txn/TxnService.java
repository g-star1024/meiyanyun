package com.meiyun.txn;

import com.meiyun.common.audit.AuditChain;
import com.meiyun.common.dualsign.*;
import com.meiyun.common.money.FeeCalculator;
import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 交易域核心服务（M4）：退款 RF / 退卡 CC。
 * 串联三条红线：① 审批状态机（L1 直达财务复核；L2/L3 店长一审 → 财务终审；任一阶段可驳回）
 * ② C-05 手续费手动窗口（退款默认 0；退卡默认 10% / 禁忌免收 / 逐单覆盖）
 * ③ 审计链（所有动作落 append-only）。
 * 签署层级阈值对齐前端设置中心活规格：L1 ¥1,000 / L2 ¥5,000 / L3 ¥20,000（金额单位分）。
 */
@Service
public class TxnService {

    /** 退款/退卡审批 tier 阈值（分），对齐前端 config/settings.ts dualSign.l1/l2/l3。 */
    static final long L1_MIN = 100_000L;
    static final long L2_MIN = 500_000L;
    static final long L3_MIN = 2_000_000L;

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TxnRefundRepository refundRepo;
    private final TxnCardCancelRepository cancelRepo;
    private final TxnOrderRepository orderRepo;
    private final MemberCardRepository cardRepo;
    private final AuditRecorder audit;
    private final ApprovalService approvalService;

    public TxnService(TxnRefundRepository refundRepo, TxnCardCancelRepository cancelRepo,
                      TxnOrderRepository orderRepo, MemberCardRepository cardRepo,
                      AuditRecorder audit, @Lazy ApprovalService approvalService) {
        this.refundRepo = refundRepo;
        this.cancelRepo = cancelRepo;
        this.orderRepo = orderRepo;
        this.cardRepo = cardRepo;
        this.audit = audit;
        this.approvalService = approvalService;
    }

    // ---------------- 退款 RF ----------------

    @Transactional
    public TxnRefund createRefund(CreateRefundCmd cmd) {
        if (cmd.refundAmt() == null || cmd.refundAmt() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退款金额必须大于 0");
        }
        if (cmd.paidAmt() != null && cmd.refundAmt() > cmd.paidAmt()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "退款金额不得超过已付金额（¥" + fenToYuan(cmd.paidAmt()) + "）");
        }
        long fee = 0L;
        if (Boolean.TRUE.equals(cmd.feeManualOverride())) {
            if (cmd.feeCents() == null || cmd.feeCents() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手动覆盖手续费时必须给出非负手续费");
            }
            fee = cmd.feeCents();
        }
        String tier = tierFor(cmd.refundAmt());
        String storeCode = resolveRefundStore(cmd.orderNo());
        TxnRefund r = new TxnRefund();
        r.setTxnNo(nextNo("RF", refundRepo::maxSeqOfDay));
        r.setOrderNo(cmd.orderNo());
        r.setStoreCode(storeCode);
        r.setCustomer(cmd.customer());
        r.setCustomerName(cmd.customerName());
        r.setProject(cmd.project());
        r.setChannel(nvl(cmd.channel(), "ORIGINAL"));
        r.setPaidAmt(cmd.paidAmt());
        r.setRefundAmt(cmd.refundAmt());
        r.setReason(cmd.reason());
        r.setFee(fee);
        r.setFeeManualOverride(Boolean.TRUE.equals(cmd.feeManualOverride()));
        r.setFeeOverrideReason(cmd.feeOverrideReason());
        r.setSignTier(tier);
        r.setApplicant(currentActor());
        r.setStatus("L1".equals(tier) ? "PENDING_FINANCE" : "PENDING_REVIEW");
        refundRepo.save(r);
        audit.record("REFUND", r.getTxnNo(), currentActor(), "CREATE",
                String.format("{\"orderNo\":%s,\"customer\":\"%s\",\"refundAmt\":%d,\"paidAmt\":%s,\"fee\":%d,\"tier\":\"%s\",\"status\":\"%s\",\"channel\":\"%s\"}",
                        jsonStr(r.getOrderNo()), r.getCustomer(), cmd.refundAmt(),
                        cmd.paidAmt() == null ? "null" : cmd.paidAmt().toString(), fee, tier, r.getStatus(), r.getChannel()));
        approvalService.submitForTxn("REFUND", r.getTxnNo(),
                "退款 · " + nvl(r.getProject(), "订单退款"),
                "客户" + nvl(r.getCustomerName(), r.getCustomer()) + "申请退款 ¥" + fenToYuan(cmd.refundAmt()),
                cmd.refundAmt(), tier, storeCode);
        return r;
    }

    // ---------------- 退卡 CC ----------------

    @Transactional
    public TxnCardCancel createCardCancel(CreateCardCancelCmd cmd) {
        if (cmd.balance() == null || cmd.balance() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "卡内余额必须大于 0");
        }
        FeeCalculator.FeePlan plan = FeeCalculator.compute(
                cmd.balance(), Boolean.TRUE.equals(cmd.medical()),
                Boolean.TRUE.equals(cmd.feeManualOverride()), cmd.feeCents());

        // 等价 chk_tk_fee_override：禁忌免收 OR 手动覆盖 OR 比例恰为 10%（与 DDL balance/10 整数除法一致）
        boolean rateOk = Boolean.TRUE.equals(cmd.medical())
                || Boolean.TRUE.equals(cmd.feeManualOverride())
                || plan.feeCents() == cmd.balance() / 10;
        if (!rateOk) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "手续费比例约束不满足（须为禁忌免收 / 手动覆盖 / 恰好 10%）");
        }

        String tier = tierFor(plan.refundCents());
        String storeCode = resolveCancelStore(cmd.cardNo());
        TxnCardCancel c = new TxnCardCancel();
        c.setTxnNo(nextNo("CC", cancelRepo::maxSeqOfDay));
        c.setCardNo(cmd.cardNo());
        c.setStoreCode(storeCode);
        c.setCustomer(cmd.customer());
        c.setCustomerName(cmd.customerName());
        c.setCardItem(cmd.cardItem());
        c.setChannel(nvl(cmd.channel(), "ORIGINAL"));
        c.setBalance(cmd.balance());
        c.setRefundAmt(plan.refundCents());
        c.setFee(plan.feeCents());
        c.setFeeRate(Boolean.TRUE.equals(cmd.medical()) ? BigDecimal.ZERO
                : (Boolean.TRUE.equals(cmd.feeManualOverride())
                    ? BigDecimal.valueOf(cmd.feeCents()).divide(BigDecimal.valueOf(cmd.balance()), 4, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(0.1)));
        c.setFeeManualOverride(Boolean.TRUE.equals(cmd.feeManualOverride()));
        c.setFeeOverrideReason(cmd.feeOverrideReason());
        c.setMedicalContraindication(Boolean.TRUE.equals(cmd.medical()));
        c.setRemainTimes(cmd.remainTimes());
        c.setSignTier(tier);
        c.setApplicant(currentActor());
        c.setStatus("L1".equals(tier) ? "PENDING_FINANCE" : "PENDING_REVIEW");
        cancelRepo.save(c);
        audit.record("CARD_CANCEL", c.getTxnNo(), currentActor(), "CREATE",
                String.format("{\"cardNo\":%s,\"customer\":\"%s\",\"balance\":%d,\"refund\":%d,\"fee\":%d,\"medical\":%b,\"tier\":\"%s\",\"status\":\"%s\",\"channel\":\"%s\"}",
                        jsonStr(c.getCardNo()), c.getCustomer(), cmd.balance(), plan.refundCents(), plan.feeCents(),
                        Boolean.TRUE.equals(cmd.medical()), tier, c.getStatus(), c.getChannel()));
        approvalService.submitForTxn("CARD_CANCEL", c.getTxnNo(),
                "退卡 · " + nvl(c.getCardItem(), "疗程卡退卡"),
                "客户" + nvl(c.getCustomerName(), c.getCustomer()) + "退卡，违约金 ¥" + fenToYuan(plan.feeCents())
                        + "，实退 ¥" + fenToYuan(plan.refundCents()),
                plan.refundCents(), tier, storeCode);
        return c;
    }

    // ---------------- 审批状态机（退款/退卡共用） ----------------

    /** 店长/运营一审通过：PENDING_REVIEW → PENDING_FINANCE。 */
    @Transactional
    public void approve(String txnNo, ApprovalCmd cmd) {
        OffsetDateTime now = OffsetDateTime.now();
        String actor = currentActor();
        if (txnNo.startsWith("RF")) {
            TxnRefund r = requireRefund(txnNo);
            if (!"PENDING_REVIEW".equals(r.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "当前状态「" + r.getStatus() + "」不可审批通过（仅待审核单据可审批）");
            }
            r.setStatus("PENDING_FINANCE");
            r.setReviewedBy(actor);
            r.setReviewedAt(now);
            refundRepo.save(r);
            audit.record("REFUND", txnNo, actor, "APPROVE",
                    "{\"from\":\"PENDING_REVIEW\",\"to\":\"PENDING_FINANCE\",\"reviewedBy\":\"" + actor + "\"}");
        } else {
            TxnCardCancel c = requireCardCancel(txnNo);
            if (!"PENDING_REVIEW".equals(c.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "当前状态「" + c.getStatus() + "」不可审批通过（仅待审核单据可审批）");
            }
            c.setStatus("PENDING_FINANCE");
            c.setReviewedBy(actor);
            c.setReviewedAt(now);
            cancelRepo.save(c);
            audit.record("CARD_CANCEL", txnNo, actor, "APPROVE",
                    "{\"from\":\"PENDING_REVIEW\",\"to\":\"PENDING_FINANCE\",\"reviewedBy\":\"" + actor + "\"}");
        }
    }

    /** 驳回：PENDING_REVIEW / PENDING_FINANCE → REJECTED（必须填驳回原因）。 */
    @Transactional
    public void reject(String txnNo, ApprovalCmd cmd) {
        if (cmd.comment() == null || cmd.comment().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回必须填写原因");
        }
        String actor = currentActor();
        String reason = cmd.comment();
        if (txnNo.startsWith("RF")) {
            TxnRefund r = requireRefund(txnNo);
            if (!"PENDING_REVIEW".equals(r.getStatus()) && !"PENDING_FINANCE".equals(r.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "当前状态「" + r.getStatus() + "」不可驳回（仅待审核/待财务复核单据可驳回）");
            }
            String from = r.getStatus();
            r.setStatus("REJECTED");
            r.setRejectionReason(reason);
            r.setRejectedBy(actor);
            if ("PENDING_REVIEW".equals(from)) {
                r.setReviewedBy(actor);
                r.setReviewedAt(OffsetDateTime.now());
            } else {
                r.setFinanceBy(actor);
            }
            refundRepo.save(r);
            audit.record("REFUND", txnNo, actor, "REJECT",
                    "{\"from\":\"" + from + "\",\"to\":\"REJECTED\",\"reason\":" + jsonStr(reason) + "}");
        } else {
            TxnCardCancel c = requireCardCancel(txnNo);
            if (!"PENDING_REVIEW".equals(c.getStatus()) && !"PENDING_FINANCE".equals(c.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "当前状态「" + c.getStatus() + "」不可驳回（仅待审核/待财务复核单据可驳回）");
            }
            String from = c.getStatus();
            c.setStatus("REJECTED");
            c.setRejectionReason(reason);
            c.setRejectedBy(actor);
            if ("PENDING_REVIEW".equals(from)) {
                c.setReviewedBy(actor);
                c.setReviewedAt(OffsetDateTime.now());
            } else {
                c.setFinanceBy(actor);
            }
            cancelRepo.save(c);
            audit.record("CARD_CANCEL", txnNo, actor, "REJECT",
                    "{\"from\":\"" + from + "\",\"to\":\"REJECTED\",\"reason\":" + jsonStr(reason) + "}");
        }
    }

    /** 财务终审确认退款/退卡完成：PENDING_FINANCE → REFUNDED。资金出入账由 M6 补。 */
    @Transactional
    public void confirmRefund(String txnNo, ApprovalCmd cmd) {
        OffsetDateTime now = OffsetDateTime.now();
        String actor = currentActor();
        if (txnNo.startsWith("RF")) {
            TxnRefund r = requireRefund(txnNo);
            if (!"PENDING_FINANCE".equals(r.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "当前状态「" + r.getStatus() + "」不可财务确认（仅待财务复核单据可确认退款）");
            }
            r.setStatus("REFUNDED");
            r.setFinanceBy(actor);
            r.setRefundedAt(now);
            refundRepo.save(r);
            audit.record("REFUND", txnNo, actor, "CONFIRM",
                    "{\"from\":\"PENDING_FINANCE\",\"to\":\"REFUNDED\",\"refundAmt\":" + r.getRefundAmt()
                            + ",\"financeBy\":\"" + actor + "\"}");
        } else {
            TxnCardCancel c = requireCardCancel(txnNo);
            if (!"PENDING_FINANCE".equals(c.getStatus())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "当前状态「" + c.getStatus() + "」不可财务确认（仅待财务复核单据可确认退卡）");
            }
            c.setStatus("REFUNDED");
            c.setFinanceBy(actor);
            c.setRefundedAt(now);
            cancelRepo.save(c);
            audit.record("CARD_CANCEL", txnNo, actor, "CONFIRM",
                    "{\"from\":\"PENDING_FINANCE\",\"to\":\"REFUNDED\",\"refundAmt\":" + c.getRefundAmt()
                            + ",\"financeBy\":\"" + actor + "\"}");
        }
    }

    public List<TxnRefund> listRefunds(String status) {
        Specification<TxnRefund> spec = DataScope.storeSpec("storeCode");
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return refundRepo.findAll(spec, Sort.by(Sort.Order.desc("txnNo")));
    }

    public List<TxnCardCancel> listCardCancels(String status) {
        Specification<TxnCardCancel> spec = DataScope.storeSpec("storeCode");
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return cancelRepo.findAll(spec, Sort.by(Sort.Order.desc("txnNo")));
    }

    // ---------------- 双签（旧端点保留，兼容既有集成） ----------------

    @Transactional
    public void sign(String txnNo, SignCommand cmd) {
        BizType biz = (txnNo.startsWith("TK") || txnNo.startsWith("CC")) ? BizType.CARD_CANCEL
                : txnNo.startsWith("RF") ? BizType.REFUND : null;
        if (biz == null) {
            throw new IllegalArgumentException("无法识别交易号前缀（须 RF/CC/TK）: " + txnNo);
        }
        long amount = biz == BizType.CARD_CANCEL
                ? cancelRepo.findById(txnNo).map(TxnCardCancel::getBalance).orElseThrow(() -> new IllegalArgumentException("退卡不存在: " + txnNo))
                : refundRepo.findById(txnNo).map(TxnRefund::getRefundAmt).orElseThrow(() -> new IllegalArgumentException("退款不存在: " + txnNo));

        SignRequest req = new SignRequest(biz, amount,
                toSigner(cmd.signer1()), toSigner(cmd.signer2()), toSigner(cmd.signer3()));
        DualSignEngine.validate(req); // 硬校验未过直接抛 DualSignException

        OffsetDateTime now = OffsetDateTime.now();
        String payload = String.format("{\"txnNo\":\"%s\",\"signer1\":\"%s\",\"signer2\":\"%s\"}",
                txnNo, cmd.signer1().personId(), cmd.signer2() == null ? "" : cmd.signer2().personId());

        if (biz == BizType.CARD_CANCEL) {
            TxnCardCancel c = cancelRepo.findById(txnNo).orElseThrow();
            c.setSign1(cmd.signer1().personId());
            c.setSignedAt1(now);
            if (cmd.signer2() != null) {
                c.setSign2(cmd.signer2().personId());
                c.setSignedAt2(now);
            }
            cancelRepo.save(c);
        } else {
            TxnRefund r = refundRepo.findById(txnNo).orElseThrow();
            r.setSign1(cmd.signer1().personId());
            r.setSignedAt1(now);
            if (cmd.signer2() != null) {
                r.setSign2(cmd.signer2().personId());
                r.setSignedAt2(now);
            }
            refundRepo.save(r);
        }
        // 落审计（双签动作本身也要留痕）：操作人取 JWT 登录人，请求体 actor 忽略
        audit.record("DUAL_SIGN", txnNo, DataScope.currentActor(), "SIGN", payload);
        // 引用 AuditChain 确保依赖可用（实际哈希在 audit-service 侧计算）
        AuditChain.genesisHash();
    }

    private Signer toSigner(SignerDto d) {
        if (d == null) return null;
        return new Signer(d.personId(), d.displayName(), d.role(), d.roleSequence(), d.storeId(), d.medicalLicensed());
    }

    public TxnRefund findRefund(String no) {
        return requireRefund(no);
    }

    public TxnCardCancel findCardCancel(String no) {
        return requireCardCancel(no);
    }

    private TxnRefund requireRefund(String no) {
        TxnRefund r = refundRepo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(r.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return r;
    }

    private TxnCardCancel requireCardCancel(String no) {
        TxnCardCancel c = cancelRepo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(c.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return c;
    }

    /** 金额（分）→ 审批签署层级，阈值对齐前端设置中心：L1 ¥1,000 / L2 ¥5,000 / L3 ¥20,000。 */
    public static String tierFor(long amountCents) {
        if (amountCents >= L3_MIN) return "L3";
        if (amountCents >= L2_MIN) return "L2";
        return "L1";
    }

    private static String fenToYuan(long fen) {
        return fen / 100 + "." + String.format("%02d", fen % 100);
    }

    /** 当前操作人：统一委托 {@link DataScope#currentActor()}（JWT 登录人工号，body actor 忽略；匿名回落 system）。 */
    private static String currentActor() {
        return DataScope.currentActor();
    }

    /** 退款单门店：按订单反查回填并做数据域闸门（越权直接 404，不泄露存在性）；无订单号时回落登录人门店。 */
    private String resolveRefundStore(String orderNo) {
        if (orderNo != null && !orderNo.isBlank()) {
            TxnOrder o = orderRepo.findById(orderNo).orElse(null);
            if (o != null && o.getStoreCode() != null && !o.getStoreCode().isBlank()) {
                if (!DataScope.canReadStore(o.getStoreCode())) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
                }
                return o.getStoreCode();
            }
        }
        var u = DataScope.current();
        return u == null ? null : u.storeCode();
    }

    /** 退卡单门店：按会员卡反查回填，规则同 {@link #resolveRefundStore}。 */
    private String resolveCancelStore(String cardNo) {
        if (cardNo != null && !cardNo.isBlank()) {
            MemberCard c = cardRepo.findById(cardNo).orElse(null);
            if (c != null && c.getStoreCode() != null && !c.getStoreCode().isBlank()) {
                if (!DataScope.canReadStore(c.getStoreCode())) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
                }
                return c.getStoreCode();
            }
        }
        var u = DataScope.current();
        return u == null ? null : u.storeCode();
    }

    private static String nvl(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 业务单号：前缀 + yyyyMMdd + '-' + 6 位当日序号（DB maxSeq+1，synchronized 防并发同号）。 */
    private synchronized String nextNo(String prefix, java.util.function.Function<String, Long> maxSeq) {
        String day = OffsetDateTime.now().format(NO_DATE);
        long next = maxSeq.apply(prefix + day + "-%") + 1;
        return prefix + day + "-" + String.format("%06d", next);
    }

    // ---------------- 命令 / DTO ----------------

    public record CreateRefundCmd(
            String actor,
            String orderNo,
            String customer,
            String customerName,
            String project,
            String channel,
            Long paidAmt,
            Long refundAmt,
            String reason,
            Boolean feeManualOverride,
            Long feeCents,
            String feeOverrideReason) {
    }

    public record CreateCardCancelCmd(
            String actor,
            String cardNo,
            String customer,
            String customerName,
            String cardItem,
            String channel,
            Long balance,
            Integer remainTimes,
            Boolean medical,
            Boolean feeManualOverride,
            Long feeCents,
            String feeOverrideReason) {
    }

    /** 审批动作命令（同意/驳回/财务确认共用；驳回时 comment 必填）。 */
    public record ApprovalCmd(String actor, String comment) {
    }

    public record SignCommand(String actor, SignerDto signer1, SignerDto signer2, SignerDto signer3) {
    }

    public record SignerDto(
            String personId, String displayName, String role, String roleSequence, String storeId, boolean medicalLicensed) {
    }
}
