package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 异常账务处置单服务（B63 卡1 L84）：长短款/错账登记 → 同步提 txn 审批
 * （bizType=FIN_ADJUSTMENT）→ 终审回调 → APPROVED 单 dispose 落 ADJUST 资金分录。
 *
 * <p>红线：人工处置全程不直接写 MATERIAL/LOSS 分录；动账只走终审通过后的 ADJUST
 * （科目默认 RF-REVENUE，source=MANUAL，不触渠道镜像/预收池联动），且用账单真实门店。
 * 登记与动账两阶段全部审计：FIN_ABNORMAL_CREATE / FIN_ABNORMAL_DISPOSE。
 */
@Service
public class FinAbnormalBillService {

    private static final Logger log = LoggerFactory.getLogger(FinAbnormalBillService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("SHORT", "LONG", "WRONG");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final FinAbnormalBillRepository billRepo;
    private final TxnApprovalClient txnApprovalClient;
    private final FundEntryService fundEntryService;
    private final FinanceAuditRecorder audit;

    public FinAbnormalBillService(FinAbnormalBillRepository billRepo,
                                  TxnApprovalClient txnApprovalClient,
                                  FundEntryService fundEntryService,
                                  FinanceAuditRecorder audit) {
        this.billRepo = billRepo;
        this.txnApprovalClient = txnApprovalClient;
        this.fundEntryService = fundEntryService;
        this.audit = audit;
    }

    /** AB+北京日 yyyyMMdd+'-'+6位序号（同事务未 flush 行对 maxSeqOfDay 不可见，序号循环外取一次）。 */
    private synchronized String nextBillNo() {
        String day = LocalDate.now(ZoneOffset.ofHours(8)).format(DAY_FMT);
        String prefix = "AB" + day + "-";
        int seq = billRepo.maxSeqOfDay(prefix + "%") + 1;
        return prefix + String.format("%06d", seq);
    }

    /**
     * 登记异常账务处置单：校验 → 门店域断言 → 落 PENDING_APPROVAL → 同步提 txn 审批。
     * txn 提交失败（4xx 透传 / 5xx 502 / 网络异常）整事务回滚，杜绝「已登记无审批单」。
     * idemKey 非空时幂等：连点/重传回返原单。
     */
    @Transactional
    public Map<String, Object> createBill(String storeCode, String type, String direction,
                                          Long amountFen, String reason, String idemKey,
                                          String actor) {
        if (storeCode == null || storeCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "门店编码不能为空");
        }
        if (!DataScope.canReadStore(storeCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常账单不存在或无权在该门店登记");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "异常类型非法：仅支持 SHORT 短款 / LONG 长款 / WRONG 错账");
        }
        if (amountFen == null || amountFen <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "金额必须为大于 0 的整数（分）");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "事由不能为空");
        }
        String useDirection = null;
        if ("WRONG".equals(type)) {
            if (!"IN".equals(direction) && !"OUT".equals(direction)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "错账单必须指定调整方向 direction：IN 补收 / OUT 冲减");
            }
            useDirection = direction;
        }
        String useIdemKey = idemKey == null || idemKey.isBlank() ? null : idemKey.trim();
        if (useIdemKey != null) {
            var existing = billRepo.findByIdemKey(useIdemKey).orElse(null);
            if (existing != null) {
                return toView(existing, true, "登记已受理，幂等返回原单");
            }
        }
        String operator = actor == null ? "system" : actor;

        FinAbnormalBill bill = new FinAbnormalBill();
        bill.setBillNo(nextBillNo());
        bill.setIdemKey(useIdemKey);
        bill.setStoreCode(storeCode.trim());
        bill.setType(type);
        bill.setDirection(useDirection);
        bill.setAmountFen(amountFen);
        bill.setSource("MANUAL");
        bill.setReason(truncate(reason.trim(), 256));
        bill.setStatus("PENDING_APPROVAL");
        bill.setCreatedBy(operator);
        bill.setCreatedAt(OffsetDateTime.now());
        billRepo.save(bill);

        String approvalNo = txnApprovalClient.submitAbnormalApproval(
                bill.getBillNo(), bill.getStoreCode(), type, amountFen, bill.getReason(), operator);
        bill.setApprovalNo(approvalNo);
        billRepo.save(bill);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("billNo", bill.getBillNo());
        payload.put("storeCode", bill.getStoreCode());
        payload.put("type", type);
        payload.put("direction", useDirection);
        payload.put("amountFen", amountFen);
        payload.put("reason", bill.getReason());
        payload.put("approvalNo", approvalNo);
        audit.record("FIN_ABNORMAL_CREATE", bill.getBillNo(), operator, "CREATE", toJson(payload));

        log.info("异常账务登记 billNo={} store={} type={} amount={} approvalNo={} actor={}",
                bill.getBillNo(), bill.getStoreCode(), type, amountFen, approvalNo, operator);
        return toView(bill, false, "登记成功，已提交审批");
    }

    /** 列表：门店数据域过滤 + 门店/状态/类型筛选，创建时间倒序。 */
    @Transactional(readOnly = true)
    public List<FinAbnormalBill> list(String storeCode, String status, String type) {
        Specification<FinAbnormalBill> spec = DataScope.storeSpec("storeCode");
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode.trim()));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status.trim()));
        }
        if (type != null && !type.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("type"), type.trim()));
        }
        return billRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("billNo")));
    }

    /** 详情：不存在/越权统一 404（不泄露存在性）。 */
    @Transactional(readOnly = true)
    public FinAbnormalBill get(String billNo) {
        FinAbnormalBill bill = billRepo.findById(billNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "异常账单不存在：billNo=" + billNo));
        if (!DataScope.canReadStore(bill.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常账单不存在或无权查看");
        }
        return bill;
    }

    /**
     * txn 终审回调（internal，幂等）：PENDING_APPROVAL → APPROVED/REJECTED。
     * 非待审批态（含重复回调）直接回返当前状态，不报错、不覆盖终审结论。
     */
    @Transactional
    public Map<String, Object> applyApprovalCallback(String billNo, boolean approved,
                                                     String reviewer, String comment) {
        FinAbnormalBill bill = billRepo.findById(billNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "异常账单不存在：billNo=" + billNo));
        if (!"PENDING_APPROVAL".equals(bill.getStatus())) {
            Map<String, Object> r = toView(bill, true,
                    "终审回调重复投递，账单已为「" + bill.getStatus() + "」，幂等忽略");
            return r;
        }
        String before = bill.getStatus();
        bill.setStatus(approved ? "APPROVED" : "REJECTED");
        bill.setReviewer(reviewer == null || reviewer.isBlank() ? "system" : reviewer);
        bill.setApprovedAt(OffsetDateTime.now(ZoneOffset.UTC));
        billRepo.save(bill);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("billNo", billNo);
        payload.put("approvalNo", bill.getApprovalNo());
        payload.put("before", before);
        payload.put("after", bill.getStatus());
        payload.put("reviewer", bill.getReviewer());
        payload.put("comment", comment);
        audit.record("FIN_ABNORMAL_APPROVAL", billNo, bill.getReviewer(),
                approved ? "APPROVE" : "REJECT", toJson(payload));

        log.info("异常账单终审回调 billNo={} {} → {} reviewer={}", billNo, before, bill.getStatus(), bill.getReviewer());
        return toView(bill, false, approved ? "终审通过，待处置入账" : "终审已驳回，不动账");
    }

    /**
     * 处置入账（仅 APPROVED）：补一条 ADJUST 调整分录（幂等 idemKey=ABNORMAL:{billNo}），
     * 用账单真实门店；成功 → DISPOSED。DISPOSED 重放幂等返回；其余状态 400。
     */
    @Transactional
    public Map<String, Object> dispose(String billNo, String actor) {
        FinAbnormalBill bill = billRepo.findById(billNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "异常账单不存在：billNo=" + billNo));
        if (!DataScope.canReadStore(bill.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常账单不存在或无权处置");
        }
        if ("DISPOSED".equals(bill.getStatus())) {
            return toView(bill, true, "账单已处置入账，幂等返回");
        }
        if ("REJECTED".equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账单已被驳回，不可处置入账");
        }
        if ("PENDING_APPROVAL".equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账单尚在审批中，终审通过后方可处置入账");
        }
        if (!"APPROVED".equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前状态「" + bill.getStatus() + "」不可处置");
        }
        String operator = actor == null ? "system" : actor;
        String direction = resolveDirection(bill);
        String idemKey = "ABNORMAL:" + billNo;
        String memo = "异常账务处置 " + billNo + " " + typeLabel(bill.getType())
                + (bill.getReason() == null ? "" : "：" + truncate(bill.getReason(), 80));
        FundEntryCmd cmd = new FundEntryCmd(idemKey, billNo, "ADJUST", "RF-REVENUE",
                direction, bill.getAmountFen(), null, "MANUAL", "ADJUST",
                bill.getStoreCode(), truncate(memo, 128), null);
        List<Map<String, Object>> posted = fundEntryService.postEntries(List.of(cmd), operator);
        Map<String, Object> entryResult = posted.get(0);
        Object entryIdObj = entryResult.get("entryId");
        Long entryId = entryIdObj instanceof Number n ? n.longValue() : null;
        boolean duplicated = Boolean.TRUE.equals(entryResult.get("duplicated"));

        bill.setStatus("DISPOSED");
        if (entryId != null) bill.setDisposeFundEntryId(entryId);
        bill.setDisposedAt(OffsetDateTime.now(ZoneOffset.UTC));
        billRepo.save(bill);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("billNo", billNo);
        payload.put("storeCode", bill.getStoreCode());
        payload.put("subject", "RF-REVENUE");
        payload.put("direction", direction);
        payload.put("amountFen", bill.getAmountFen());
        payload.put("fundEntryId", entryId);
        payload.put("duplicated", duplicated);
        audit.record("FIN_ABNORMAL_DISPOSE", billNo, operator, "DISPOSE", toJson(payload));

        log.info("异常账单处置入账 billNo={} {} {}/{} entryId={} actor={}",
                billNo, bill.getStoreCode(), direction, bill.getAmountFen(), entryId, operator);
        Map<String, Object> r = toView(bill, duplicated, "处置完成，调整分录已入账");
        r.put("entry", entryResult);
        return r;
    }

    /** SHORT 短款=现金短少 OUT；LONG 长款=现金溢余 IN；WRONG 取登记方向。 */
    private static String resolveDirection(FinAbnormalBill bill) {
        return switch (bill.getType()) {
            case "SHORT" -> "OUT";
            case "LONG" -> "IN";
            default -> bill.getDirection();
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "SHORT" -> "短款";
            case "LONG" -> "长款";
            default -> "错账";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String toJson(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            log.error("异常账单审计 payload JSON 序列化失败: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "审计内容序列化失败");
        }
    }

    private Map<String, Object> toView(FinAbnormalBill bill, boolean duplicated, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("billNo", bill.getBillNo());
        m.put("storeCode", bill.getStoreCode());
        m.put("type", bill.getType());
        m.put("direction", bill.getDirection());
        m.put("amountFen", bill.getAmountFen());
        m.put("source", bill.getSource());
        m.put("outboxId", bill.getOutboxId());
        m.put("reason", bill.getReason());
        m.put("status", bill.getStatus());
        m.put("approvalNo", bill.getApprovalNo());
        m.put("disposeFundEntryId", bill.getDisposeFundEntryId());
        m.put("createdBy", bill.getCreatedBy());
        m.put("reviewer", bill.getReviewer());
        m.put("createdAt", bill.getCreatedAt());
        m.put("approvedAt", bill.getApprovedAt());
        m.put("disposedAt", bill.getDisposedAt());
        m.put("duplicated", duplicated);
        m.put("message", message);
        return m;
    }
}
