package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M4-18 复购回访服务（P5-B64 卡1 从 M4RepurchaseController 下沉）：
 * 落单 / 查询 / 三方签核；大额（转移金额 ≥ L1 ¥1000）三签齐不直接并账，
 * 置「审批中」同事务调 {@link ApprovalService#submitRepurchase} 建多级审批待办，
 * 终审 APPROVED 由审批域回调 {@link RepurchaseTransferExecutor} 同事务并账，任一阶段驳回置「已拒绝」不并账。
 *
 * 红线：
 * ① 知情同意书未签不得创建；② 三方签两两不得同一人；
 * ③ 小额（&lt;¥1000）三签即并账置「已完成」；大额审批中余额/次数不动；
 * ④ 非「待签核」状态再次签核一律 409（含审批中/已完成/已拒绝，不可重提不可重签）。
 */
@Service
public class RepurchaseService {

    private final RepurchaseRepository repo;
    private final AuditRecorder audit;
    private final RepurchaseTransferExecutor transferExecutor;
    private final ApprovalService approvalService;
    private final AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000);

    public RepurchaseService(RepurchaseRepository repo, AuditRecorder audit,
                             RepurchaseTransferExecutor transferExecutor,
                             @Lazy ApprovalService approvalService) {
        this.repo = repo;
        this.audit = audit;
        this.transferExecutor = transferExecutor;
        this.approvalService = approvalService;
    }

    @Transactional
    public Repurchase create(RepurchaseCmd cmd) {
        if (!cmd.consentAck()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "知情同意书未签署，不得创建复购/资产转移单");
        }
        if ("资产转移".equals(cmd.bizType())
                && (cmd.fromCardNo() == null || cmd.toCardNo() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "资产转移须指定来源卡与目标卡");
        }
        Repurchase r = new Repurchase();
        r.setRepurchaseNo(nextNo("RP"));
        r.setCustomerId(cmd.customerId());
        r.setStoreCode(cmd.storeCode());
        r.setBizType(cmd.bizType());
        r.setTargetProject(cmd.targetProject());
        r.setFromCardNo(cmd.fromCardNo());
        r.setToCardNo(cmd.toCardNo());
        r.setTransferTimes(cmd.transferTimes() == null ? 0 : cmd.transferTimes());
        r.setTransferAmount(cmd.transferAmount() == null ? 0L : cmd.transferAmount());
        r.setConsentAck(true);
        r.setConsentText(cmd.consentText());
        r.setStatus("待签核");
        r.setCreatedAt(OffsetDateTime.now());
        r.setNote(cmd.note());
        repo.save(r);
        audit.record("REPURCHASE", r.getRepurchaseNo(), DataScope.currentActor(),
                "CREATE", "{\"bizType\":\"" + cmd.bizType() + "\",\"consent\":true}");
        return r;
    }

    public List<Repurchase> list() {
        return repo.findAll(DataScope.storeSpec("storeCode"),
                Sort.by(Sort.Order.desc("createdAt")));
    }

    public Repurchase require(String no) {
        Repurchase r = repo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(r.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return r;
    }

    /**
     * 三方签核：sign1 客户确认 + sign2 经办 + sign3 店长，两两不得同一人。
     * 驳回（reject=true）仅录 sign1 置「已拒绝」；三签齐按转移金额分流——
     * &lt;¥1000（TxnService.L1_MIN）即时并账置「已完成」；≥¥1000 置「审批中」建审批待办不并账。
     * 非「待签核」状态（审批中/已完成/已拒绝）再次签核一律 409。
     */
    @Transactional
    public Repurchase sign(String no, @Valid TripleSignCmd cmd) {
        Repurchase r = require(no);
        if (!"待签核".equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "单据当前状态为「" + r.getStatus() + "」，不可重复签核");
        }
        if (!r.isConsentAck()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知情同意书未签，禁止签核");
        }
        if (!hasText(cmd.sign1()) || !hasText(cmd.sign2()) || !hasText(cmd.sign3())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "三方双签须三签齐全");
        }
        if (cmd.sign1().equals(cmd.sign2()) || cmd.sign1().equals(cmd.sign3()) || cmd.sign2().equals(cmd.sign3())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "三方签不得有同一人");
        }
        if (cmd.reject()) {
            r.setStatus("已拒绝");
            r.setSign1(cmd.sign1());
            r.setSign1Role(cmd.sign1Role());
            r.setSignedAt1(OffsetDateTime.now());
            repo.save(r);
            audit.record("REPURCHASE", no, DataScope.currentActor(), "REJECT",
                    "{\"sign1\":\"" + cmd.sign1() + "\"}");
            return r;
        }

        OffsetDateTime now = OffsetDateTime.now();
        r.setSign1(cmd.sign1()); r.setSign1Role(nvl(cmd.sign1Role(), "客户"));
        r.setSign2(cmd.sign2()); r.setSign2Role(nvl(cmd.sign2Role(), "经办"));
        r.setSign3(cmd.sign3()); r.setSign3Role(nvl(cmd.sign3Role(), "店长"));
        r.setSignedAt1(now); r.setSignedAt2(now); r.setSignedAt3(now);

        long amount = r.getTransferAmount() == null ? 0L : r.getTransferAmount();
        String signJson = "{\"sign1\":\"" + cmd.sign1() + "\",\"sign2\":\"" + cmd.sign2()
                + "\",\"sign3\":\"" + cmd.sign3() + "\",\"bizType\":\"" + r.getBizType() + "\"}";
        if (amount < TxnService.L1_MIN) {
            // 小额：三签即终审，同事务并账置「已完成」
            transferExecutor.executeTransfer(r);
            r.setStatus("已完成");
            repo.save(r);
            audit.record("REPURCHASE", no, DataScope.currentActor(), "TRIPLE_SIGN", signJson);
            return r;
        }

        // 大额：置「审批中」不并账，同事务建多级审批待办（终审回调才搬卡账）
        r.setStatus("审批中");
        repo.save(r);
        audit.record("REPURCHASE", no, DataScope.currentActor(), "TRIPLE_SIGN", signJson);
        approvalService.submitRepurchase(r);
        audit.record("REPURCHASE", no, DataScope.currentActor(), "APPROVAL_SUBMIT",
                "{\"amount\":" + amount + ",\"tier\":\"" + TxnService.tierFor(amount) + "\"}");
        return r;
    }

    /**
     * 终审 APPROVED 回调（仅 ApprovalService 终审阶段调用，同事务）：
     * 状态闸防重——非「审批中」抛 409；执行并账后置「已完成」并审计 APPROVAL_PASS。
     */
    @Transactional
    public Repurchase applyApproved(String repurchaseNo, String actor, String comment) {
        Repurchase r = repo.findById(repurchaseNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "复购单不存在: " + repurchaseNo));
        if (!"审批中".equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "复购单当前状态为「" + r.getStatus() + "」，审批终审无法回写");
        }
        transferExecutor.executeTransfer(r);
        r.setStatus("已完成");
        repo.save(r);
        audit.record("REPURCHASE", repurchaseNo, actor, "APPROVAL_PASS",
                "{\"comment\":\"" + (comment == null ? "" : comment.replace("\"", "\\\"")) + "\"}");
        return r;
    }

    /**
     * 任一阶段驳回回调（同事务）：状态闸防重——非「审批中」抛 409；置「已拒绝」不并账并审计 APPROVAL_REJECT。
     */
    @Transactional
    public Repurchase applyRejected(String repurchaseNo, String actor, String reason) {
        Repurchase r = repo.findById(repurchaseNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "复购单不存在: " + repurchaseNo));
        if (!"审批中".equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "复购单当前状态为「" + r.getStatus() + "」，审批驳回无法回写");
        }
        r.setStatus("已拒绝");
        repo.save(r);
        audit.record("REPURCHASE", repurchaseNo, actor, "APPROVAL_REJECT",
                "{\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "\\\"")) + "\"}");
        return r;
    }

    private String nextNo(String prefix) {
        long n = seq.incrementAndGet() % 1_000_000;
        return prefix + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", n);
    }

    private String nvl(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    public record RepurchaseCmd(
            @NotBlank String customerId, @NotBlank String storeCode,
            @NotBlank String bizType, String targetProject,
            String fromCardNo, String toCardNo,
            Integer transferTimes, Long transferAmount,
            @NotNull Boolean consentAck, String consentText, String note) {}

    public record TripleSignCmd(
            String sign1, String sign1Role,
            String sign2, String sign2Role,
            String sign3, String sign3Role,
            boolean reject) {}
}
