package com.meiyun.txn;

import com.meiyun.common.dualsign.BizType;
import com.meiyun.common.dualsign.DualSignEngine;
import com.meiyun.common.dualsign.SignRequest;
import com.meiyun.common.dualsign.Signer;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M4-18 复购回访（复购/资产转移，三方双签 + 知情同意书）
 * + 双签 5 类补齐（耗材领用/报损/现金交接，不看金额一律双签）。
 *
 * 红线：
 * ① 知情同意书未签（consentAck=false）不得创建复购/资产转移单；
 * ② 三方签：客户确认 + 经办 + 店长，三方不得同一人；
 * ③ 资产转移完成时同事务搬移来源卡余额/次数到目标卡（账实校验）；
 * ④ 耗材领用/报损的第二签须持医疗执业资质（DualSignEngine MEDICAL）；
 * ⑤ 全部动作落 append-only 审计链。
 */
@RestController
@RequestMapping("/api/txn")
public class M4RepurchaseController {

    private static final Map<String, BizType> TICKET_BIZ = Map.of(
            "耗材领用", BizType.CONSUMABLE,
            "报损", BizType.SCRAP,
            "现金交接", BizType.CASH_HANDOVER);

    private final RepurchaseRepository repurchaseRepo;
    private final DualSignTicketRepository ticketRepo;
    private final MemberCardRepository cardRepo;
    private final AuditRecorder audit;
    private final AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000);

    public M4RepurchaseController(RepurchaseRepository repurchaseRepo,
                                  DualSignTicketRepository ticketRepo,
                                  MemberCardRepository cardRepo, AuditRecorder audit) {
        this.repurchaseRepo = repurchaseRepo;
        this.ticketRepo = ticketRepo;
        this.cardRepo = cardRepo;
        this.audit = audit;
    }

    // ==================== M4-18 复购回访 ====================

    @PostMapping("/repurchase")
    @RequirePerm("followup:create")
    public Repurchase createRepurchase(@RequestBody @Valid RepurchaseCmd cmd) {
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
        repurchaseRepo.save(r);
        audit.record("REPURCHASE", r.getRepurchaseNo(), DataScope.currentActor(),
                "CREATE", "{\"bizType\":\"" + cmd.bizType() + "\",\"consent\":true}");
        return r;
    }

    @GetMapping("/repurchase")
    @RequirePerm("followup:view")
    public List<Repurchase> listRepurchase() {
        return repurchaseRepo.findAll(DataScope.storeSpec("storeCode"),
                Sort.by(Sort.Order.desc("createdAt")));
    }

    @GetMapping("/repurchase/{no}")
    @RequirePerm("followup:view")
    public Repurchase getRepurchase(@PathVariable String no) {
        Repurchase r = repurchaseRepo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(r.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return r;
    }

    /**
     * 三方双签：sign1 客户确认 + sign2 经办（咨询师/前台）+ sign3 店长。
     * 三方不得同一人；资产转移完成时同事务搬移卡余额/次数。
     */
    @PostMapping("/repurchase/{no}/sign")
    @RequirePerm("followup:edit")
    @Transactional
    public Repurchase signRepurchase(@PathVariable String no, @RequestBody @Valid TripleSignCmd cmd) {
        Repurchase r = getRepurchase(no);
        if (!"待签核".equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单据状态须为「待签核」，当前: " + r.getStatus());
        }
        if (!r.isConsentAck()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知情同意书未签，禁止签核");
        }
        if (cmd.sign1() == null || cmd.sign2() == null || cmd.sign3() == null) {
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
            repurchaseRepo.save(r);
            audit.record("REPURCHASE", no, DataScope.currentActor(), "REJECT",
                    "{\"sign1\":\"" + cmd.sign1() + "\"}");
            return r;
        }

        OffsetDateTime now = OffsetDateTime.now();
        r.setSign1(cmd.sign1()); r.setSign1Role(nvl(cmd.sign1Role(), "客户"));
        r.setSign2(cmd.sign2()); r.setSign2Role(nvl(cmd.sign2Role(), "经办"));
        r.setSign3(cmd.sign3()); r.setSign3Role(nvl(cmd.sign3Role(), "店长"));
        r.setSignedAt1(now); r.setSignedAt2(now); r.setSignedAt3(now);
        r.setStatus("已完成");

        // 资产转移：同事务搬移卡余额/次数（账实校验）
        if ("资产转移".equals(r.getBizType())) {
            MemberCard from = cardRepo.findById(r.getFromCardNo())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "来源卡不存在: " + r.getFromCardNo()));
            MemberCard to = cardRepo.findById(r.getToCardNo())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "目标卡不存在: " + r.getToCardNo()));
            if (from.getRemainTimes() < r.getTransferTimes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "账实校验失败：来源卡剩余次数 " + from.getRemainTimes()
                                + " < 转移次数 " + r.getTransferTimes());
            }
            if (from.getBalance() < r.getTransferAmount()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "账实校验失败：来源卡余额 " + from.getBalance()
                                + " 分 < 转移金额 " + r.getTransferAmount() + " 分");
            }
            from.setRemainTimes(from.getRemainTimes() - r.getTransferTimes());
            from.setBalance(from.getBalance() - r.getTransferAmount());
            if (from.getRemainTimes() == 0 && from.getBalance() == 0) {
                from.setStatus("已用完");
            }
            to.setRemainTimes(to.getRemainTimes() + r.getTransferTimes());
            to.setBalance(to.getBalance() + r.getTransferAmount());
            cardRepo.save(from);
            cardRepo.save(to);
        }

        repurchaseRepo.save(r);
        audit.record("REPURCHASE", no, DataScope.currentActor(), "TRIPLE_SIGN",
                "{\"sign1\":\"" + cmd.sign1() + "\",\"sign2\":\"" + cmd.sign2()
                        + "\",\"sign3\":\"" + cmd.sign3() + "\",\"bizType\":\"" + r.getBizType() + "\"}");
        return r;
    }

    // ==================== 双签工单（耗材领用/报损/现金交接） ====================

    @PostMapping("/dualsign-ticket")
    @RequirePerm({"requisition:create", "wastage:create", "handover:create"})
    public DualSignTicket createTicket(@RequestBody @Valid TicketCmd cmd) {
        if (!TICKET_BIZ.containsKey(cmd.bizType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "工单业务类型仅支持：耗材领用/报损/现金交接");
        }
        DualSignTicket t = new DualSignTicket();
        t.setTicketNo(nextNo("DS"));
        t.setBizType(cmd.bizType());
        t.setStoreCode(cmd.storeCode());
        t.setTitle(cmd.title());
        t.setAmount(cmd.amount() == null ? 0L : cmd.amount());
        t.setStatus("待签核");
        t.setNote(cmd.note());
        t.setCreatedAt(OffsetDateTime.now());
        ticketRepo.save(t);
        audit.record("DUAL_SIGN", t.getTicketNo(), DataScope.currentActor(),
                "CREATE", "{\"bizType\":\"" + cmd.bizType() + "\",\"amount\":" + t.getAmount() + "}");
        return t;
    }

    @GetMapping("/dualsign-ticket")
    @RequirePerm({"requisition:view", "wastage:view", "handover:view"})
    public List<DualSignTicket> listTicket(@RequestParam(required = false) String bizType) {
        Specification<DualSignTicket> spec = DataScope.storeSpec("storeCode");
        if (bizType != null && !bizType.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("bizType"), bizType));
        }
        return ticketRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
    }

    /**
     * 现金日结汇总（M2 交接班配套）：按门店聚合指定日期「现金交接」类已完成双签工单。
     *
     * @param date  结算日期（yyyy-MM-dd），默认今天
     * @param store 可选门店编码，不传则全部门店
     */
    @GetMapping("/cash-settle")
    @RequirePerm("daily:view")
    public Map<String, Object> cashSettle(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String store) {
        String day = (date == null || date.isBlank())
                ? OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE) : date;
        List<DualSignTicketRepository.CashSettleRow> rows = cashSettleRows(day, store);
        String effStore = (store == null || store.isBlank()) ? resolveDefaultStore() : store;
        long grandTotal = rows.stream().mapToLong(DualSignTicketRepository.CashSettleRow::getTotalAmount).sum();
        long grandCount = rows.stream().mapToLong(DualSignTicketRepository.CashSettleRow::getTicketCount).sum();
        return Map.of(
                "date", day,
                "store", effStore == null ? "ALL" : effStore,
                "currency", "CNY",
                "unit", "分",
                "details", rows,
                "totalAmount", grandTotal,
                "totalAmountYuan", grandTotal / 100.0,
                "ticketCount", grandCount);
    }

    /**
     * 双签：不看金额一律双签（DualSignEngine FORCE_DUAL）；
     * 耗材领用/报损第二签须持医疗执业资质（MEDICAL）。
     */
    @PostMapping("/dualsign-ticket/{no}/sign")
    @RequirePerm({"requisition:sign", "wastage:sign", "handover:edit"})
    @Transactional
    public DualSignTicket signTicket(@PathVariable String no, @RequestBody @Valid TicketSignCmd cmd) {
        DualSignTicket t = ticketRepo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(t.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        if (!"待签核".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "工单状态须为「待签核」，当前: " + t.getStatus());
        }
        if (cmd.reject()) {
            t.setStatus("已拒绝");
            t.setSign1(cmd.sign1Name()); t.setSign1Role(cmd.sign1Role());
            t.setSignedAt1(OffsetDateTime.now());
            ticketRepo.save(t);
            audit.record("DUAL_SIGN", no, DataScope.currentActor(), "REJECT", "{}");
            return t;
        }
        BizType biz = TICKET_BIZ.get(t.getBizType());
        SignRequest req = new SignRequest(biz, t.getAmount(),
                new Signer(cmd.sign1Id(), cmd.sign1Name(), cmd.sign1Role(),
                        nvl(cmd.sign1Seq(), cmd.sign1Role()), t.getStoreCode(), false),
                cmd.sign2Id() == null ? null : new Signer(cmd.sign2Id(), cmd.sign2Name(),
                        cmd.sign2Role(), nvl(cmd.sign2Seq(), cmd.sign2Role()),
                        t.getStoreCode(), Boolean.TRUE.equals(cmd.sign2Licensed())),
                null);
        List<String> errors = DualSignEngine.validateReturningErrors(req);
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "双签校验未通过：" + String.join("; ", errors));
        }
        OffsetDateTime now = OffsetDateTime.now();
        t.setSign1(cmd.sign1Name()); t.setSign1Role(cmd.sign1Role()); t.setSignedAt1(now);
        t.setSign2(cmd.sign2Name()); t.setSign2Role(cmd.sign2Role()); t.setSignedAt2(now);
        t.setStatus("已完成");
        ticketRepo.save(t);
        audit.record("DUAL_SIGN", no, DataScope.currentActor(), "DUAL_SIGN",
                "{\"bizType\":\"" + t.getBizType() + "\",\"sign1\":\"" + cmd.sign1Name()
                        + "\",\"sign2\":\"" + cmd.sign2Name() + "\"}");
        return t;
    }

    // ==================== 内部方法 ====================

    private String nextNo(String prefix) {
        long n = seq.incrementAndGet() % 1_000_000;
        return prefix + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", n);
    }

    private String nvl(String v, String def) { return v == null || v.isBlank() ? def : v; }

    /** 现金日结数据域收敛：显式门店先越权断言（他店统一 404 不泄露存在性）；STORE/SELF 强制本店、无店返空；REGION 仅可见区域门店，不传则逐店汇总；GROUP/BRAND 全量。 */
    private List<DualSignTicketRepository.CashSettleRow> cashSettleRows(String day, String store) {
        var user = DataScope.current();
        boolean explicitStore = store != null && !store.isBlank();
        if (explicitStore && !DataScope.canReadStore(store)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        if (user == null || user.isSuper()) return ticketRepo.cashSettleByDate(day, store);
        if (DataScope.SCOPE_STORE.equals(user.scope()) || DataScope.SCOPE_SELF.equals(user.scope())) {
            String sc = user.storeCode();
            if (sc == null || sc.isBlank()) {
                return List.of();
            }
            return ticketRepo.cashSettleByDate(day, sc);
        }
        if (explicitStore) {
            return ticketRepo.cashSettleByDate(day, store);
        }
        if (DataScope.SCOPE_REGION.equals(user.scope()) && user.stores() != null && !user.stores().isEmpty()) {
            List<DualSignTicketRepository.CashSettleRow> all = new java.util.ArrayList<>();
            for (String sc : user.stores()) {
                all.addAll(ticketRepo.cashSettleByDate(day, sc));
            }
            return all;
        }
        return ticketRepo.cashSettleByDate(day, null);
    }

    private String resolveDefaultStore() {
        var user = DataScope.current();
        if (user != null && (DataScope.SCOPE_STORE.equals(user.scope()) || DataScope.SCOPE_SELF.equals(user.scope()))) {
            return user.storeCode();
        }
        return null;
    }

    // ==================== 命令 DTO ====================

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

    public record TicketCmd(
            @NotBlank String bizType, @NotBlank String storeCode,
            @NotBlank String title, Long amount, String note, String operator) {}

    public record TicketSignCmd(
            String sign1Id, String sign1Name, String sign1Role, String sign1Seq,
            String sign2Id, String sign2Name, String sign2Role, String sign2Seq,
            Boolean sign2Licensed, boolean reject) {}
}
