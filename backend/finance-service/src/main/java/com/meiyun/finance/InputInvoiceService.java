package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 进项发票登记簿后端（B63 卡4 L86，V39 fin_input_invoice）。
 *
 * <p>链路：登记（UNCONFIRMED）→ 用途确认 confirm（DEDUCT 抵扣 / NO_DEDUCT 不抵扣 / REFUND 退税）
 * → 抵扣 deduct（须归属 OPEN 申报期，申报后锁抵扣）→ 进项转出 transferOut。
 * 不抵扣（NON_DEDUCTIBLE）为终态旁路；金额 Long「分」，服务端价税分离，不信任客户端；
 * 视图金额返回「元」。登记幂等键重复回 409 中文，状态机非法流转回 422 中文。
 *
 * <p>税率五档与价税分离算法与 {@link FinConfigService} 保持一致（该处工具为 private，按同范式自有一份，
 * 不通过反射/改可见性跨服务耦合）。
 */
@Service
public class InputInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InputInvoiceService.class);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_MAX = 100;
    private static final Pattern MONTH_LABEL = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final Pattern QUARTER_LABEL = Pattern.compile("\\d{4}-Q[1-4]");

    private static final Set<String> KINDS = Set.of("SPECIAL", "CUSTOMS", "TOLL", "PASSENGER", "OTHER");
    private static final Set<String> CATEGORIES = Set.of("SERVICE", "PRODUCT", "MEMBERSHIP");
    private static final Set<String> CONFIRM_PURPOSES = Set.of("DEDUCT", "NO_DEDUCT", "REFUND");
    private static final Set<String> REASONS = Set.of(
            "WELFARE", "LOSS_GOODS", "LOSS_PRODUCT", "LOSS_REAL_ESTATE",
            "LOSS_CONSTRUCTION", "LOAN_DAILY", "OTHER");
    /** 参与重复入账防控的活跃四态（与 V39 uk_fin_input_invoice_dedup 部分索引一致）。 */
    private static final List<String> ACTIVE_STATUSES =
            List.of("UNCONFIRMED", "CONFIRMED", "DEDUCTED", "TRANSFERRED_OUT");
    private static final Set<BigDecimal> INVOICE_RATES =
            Set.of(bd("0"), bd("0.01"), bd("0.03"), bd("0.06"), bd("0.13"));

    private static final Map<String, String> KIND_LABEL = Map.of(
            "SPECIAL", "增值税专用发票",
            "CUSTOMS", "海关进口增值税专用缴款书",
            "TOLL", "通行费电子普通发票",
            "PASSENGER", "旅客运输凭证",
            "OTHER", "其他扣税凭证");
    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "SERVICE", "服务", "PRODUCT", "商品", "MEMBERSHIP", "会员");
    private static final Map<String, String> PURPOSE_LABEL = Map.of(
            "PENDING", "待确认", "DEDUCT", "抵扣", "NO_DEDUCT", "不抵扣", "REFUND", "退税");
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "UNCONFIRMED", "待确认",
            "CONFIRMED", "已用途确认",
            "DEDUCTED", "已抵扣",
            "TRANSFERRED_OUT", "已进项转出",
            "NON_DEDUCTIBLE", "不抵扣");
    private static final Map<String, String> REASON_LABEL = Map.ofEntries(
            Map.entry("WELFARE", "简易计税/免税/集体福利/个人消费"),
            Map.entry("LOSS_GOODS", "非正常损失购进货物及相关劳务/运输"),
            Map.entry("LOSS_PRODUCT", "非正常损失在产品/产成品耗用购进"),
            Map.entry("LOSS_REAL_ESTATE", "非正常损失不动产及所耗购进/设计/建筑"),
            Map.entry("LOSS_CONSTRUCTION", "非正常损失不动产在建工程所耗"),
            Map.entry("LOAN_DAILY", "贷款/餐饮/居民日常/娱乐服务"),
            Map.entry("OTHER", "其他依法不得抵扣情形"));

    private final FinInputInvoiceRepository repo;
    private final TaxPeriodService taxPeriods;
    private final FinanceAggregationService aggregation;
    private final FinanceAuditRecorder audit;
    private final ObjectMapper objectMapper;

    public InputInvoiceService(FinInputInvoiceRepository repo, TaxPeriodService taxPeriods,
                               FinanceAggregationService aggregation, FinanceAuditRecorder audit,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.taxPeriods = taxPeriods;
        this.aggregation = aggregation;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 ====================

    /** 进项发票分页（元，门店中文名已解析）；数据域强制收敛，可按门店/状态/票种/用途/归属期/关键词过滤。 */
    public Page<Map<String, Object>> list(String storeCode, String status, String invoiceKind,
                                          String purpose, Long periodId, String keyword,
                                          int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Page<FinInputInvoice> rows = repo.findAll((root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(DataScope.<FinInputInvoice>storeSpec("storeCode").toPredicate(root, q, cb));
            if (storeCode != null && !storeCode.isBlank()) {
                ps.add(cb.equal(root.get("storeCode"), storeCode.trim()));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status.trim()));
            }
            if (invoiceKind != null && !invoiceKind.isBlank()) {
                ps.add(cb.equal(root.get("invoiceKind"), invoiceKind.trim()));
            }
            if (purpose != null && !purpose.isBlank()) {
                ps.add(cb.equal(root.get("purpose"), purpose.trim()));
            }
            if (periodId != null) {
                ps.add(cb.equal(root.get("periodId"), periodId));
            }
            if (keyword != null && !keyword.isBlank()) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("registerNo")), kw),
                        cb.like(cb.lower(root.get("invoiceNo")), kw),
                        cb.like(cb.lower(root.get("sellerName")), kw),
                        cb.like(cb.lower(cb.coalesce(root.get("sellerTaxNo"), "")), kw)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        }, PageRequest.of(p, s, Sort.by(Sort.Order.desc("id"))));

        Set<String> codes = new LinkedHashSet<>();
        rows.forEach(e -> codes.add(e.getStoreCode()));
        Map<String, String> names = aggregation.resolveStoreNames(new ArrayList<>(codes));
        return rows.map(e -> invoiceView(e, names));
    }

    /** 详情（404＋数据域 403）。 */
    public Map<String, Object> get(Long id) {
        FinInputInvoice e = mustInput(id);
        if (!DataScope.canReadStore(e.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该门店进项发票");
        }
        return invoiceView(e, aggregation.resolveStoreNames(List.of(e.getStoreCode())));
    }

    /**
     * 进项抵扣汇总（元）。periodId 优先；其次 type＋period 标识；均缺省取当前月开放期。
     * 期间未懒创建时不新建（GET 语义），返回 exists=false 零金额骨架；待抵扣为全局口径不受期间影响。
     */
    public Map<String, Object> summary(Long periodId, String type, String label) {
        FinTaxPeriod period = resolvePeriodForQuery(periodId, type, label);
        Map<String, Object> out = new LinkedHashMap<>();
        if (period == null) {
            String t = normalizeType(type);
            String l = (label == null || label.isBlank())
                    ? LocalDate.now(CN).format(DateTimeFormatter.ofPattern("yyyy-MM"))
                    : label.trim();
            out.putAll(taxPeriods.blankPeriodView(t, l));
            out.put("exists", false);
            out.put("periodId", null);
            out.put("inputAmount", 0.0);
            out.put("deducted", 0.0);
            out.put("transferredOut", 0.0);
            out.put("netDeductible", 0.0);
            out.put("outputAmount", 0.0);
            out.put("payableAmount", 0.0);
            out.put("retainedAmount", 0.0);
        } else {
            long[] snap = taxPeriods.snapshot(period);
            out.put("periodType", period.getPeriodType());
            out.put("period", period.getPeriod());
            out.put("periodId", period.getId());
            out.put("exists", true);
            out.put("status", period.getStatus());
            out.put("statusLabel", TaxPeriodService.periodStatusLabel(period.getStatus()));
            out.put("inputAmount", yuan(repo.sumAmountByPeriod(period.getId())));
            out.put("deducted", yuan(snap[1] + snap[2]));
            out.put("transferredOut", yuan(snap[2]));
            out.put("netDeductible", yuan(snap[1]));
            out.put("outputAmount", yuan(snap[0]));
            out.put("payableAmount", yuan(snap[3]));
            out.put("retainedAmount", yuan(snap[4]));
        }
        out.put("deductibleConfirmed", yuan(repo.sumConfirmedDeductTax()));
        return out;
    }

    // ==================== 登记 ====================

    /** 登记进项发票（UNCONFIRMED）。登记号/税额服务端生成；idem_key 重复 409；活跃同票重复 422。 */
    @Transactional
    public Map<String, Object> register(Map<String, Object> body, String actor) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "登记内容不能为空");
        }
        String idemKey = str(body.get("idemKey"));
        if (idemKey == null || idemKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "幂等键不能为空");
        }
        idemKey = truncate(idemKey.trim(), 80);
        if (repo.findByIdemKey(idemKey).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该登记请求已提交（幂等键重复），请勿重复登记");
        }

        String kind = str(body.get("invoiceKind"));
        if (kind == null || !KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "扣税凭证种类不合法（SPECIAL/CUSTOMS/TOLL/PASSENGER/OTHER）");
        }
        String category = str(body.get("category"));
        if (category == null || !CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "采购用途不合法（SERVICE/PRODUCT/MEMBERSHIP）");
        }
        String sellerName = str(body.get("sellerName"));
        if (sellerName == null || sellerName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "开票方名称不能为空");
        }
        String sellerTaxNo = str(body.get("sellerTaxNo"));
        if (sellerTaxNo == null || sellerTaxNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "开票方纳税人识别号不能为空");
        }
        sellerTaxNo = sellerTaxNo.trim();
        String invoiceNo = str(body.get("invoiceNo"));
        if (invoiceNo == null || invoiceNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "发票号码不能为空");
        }
        invoiceNo = invoiceNo.trim();
        String storeCode = str(body.get("storeCode"));
        if (storeCode == null || storeCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "归属门店不能为空");
        }
        if (!DataScope.canReadStore(storeCode.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权在该门店登记进项发票");
        }
        storeCode = storeCode.trim();
        LocalDate invoiceDate = parseDate(body.get("invoiceDate"), "开票日期");
        long amount = parsePositiveFen(body.get("amount"), "价税合计");
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "价税合计须大于 0");
        }
        BigDecimal rate = parseInvoiceRate(body.get("taxRate"));

        long computedTax = computeTaxFen(amount, rate);
        long taxAmount = computedTax;
        Object taxOverride = body.get("taxAmount");
        if (taxOverride != null && !String.valueOf(taxOverride).isBlank()) {
            long ticketTax = parsePositiveFen(taxOverride, "票面税额");
            if (ticketTax > amount) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "票面税额不能大于价税合计");
            }
            if (Math.abs(ticketTax - computedTax) > 1) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "票面税额与按税率计算税额相差超过 1 分，请核对税率或票面金额");
            }
            taxAmount = ticketTax;
        }
        long netAmount = amount - taxAmount;

        if (repo.existsBySellerTaxNoAndInvoiceNoAndStatusIn(sellerTaxNo, invoiceNo, ACTIVE_STATUSES)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "该销方税号＋发票号码已在进项台账登记（活跃状态），不可重复入账");
        }

        FinInputInvoice e = new FinInputInvoice();
        e.setRegisterNo(nextRegisterNo());
        e.setInvoiceCode(trimTo(str(body.get("invoiceCode")), 32));
        e.setInvoiceNo(truncate(invoiceNo, 32));
        e.setInvoiceKind(kind);
        e.setSellerName(truncate(sellerName.trim(), 128));
        e.setSellerTaxNo(truncate(sellerTaxNo, 32));
        e.setSupplierId(parseLongOrNull(body.get("supplierId"), "供应商"));
        e.setAmount(amount);
        e.setNetAmount(netAmount);
        e.setTaxAmount(taxAmount);
        e.setTaxRate(rate);
        e.setCategory(category);
        e.setPurpose("PENDING");
        e.setStatus("UNCONFIRMED");
        e.setTransferOutAmount(0L);
        e.setInvoiceDate(invoiceDate);
        e.setStoreCode(storeCode);
        e.setOperator(truncate(actor, 64) == null ? "system" : truncate(actor, 64));
        e.setRemark(trimTo(str(body.get("remark")), 256));
        e.setIdemKey(idemKey);
        e.setCreatedBy(actor == null ? "system" : truncate(actor, 16));
        e.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
        try {
            repo.save(e);
        } catch (DataIntegrityViolationException ex) {
            if (repo.findByIdemKey(idemKey).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该登记请求已提交（幂等键重复），请勿重复登记");
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "该进项发票登记与已有记录冲突（重复发票或并发登记），请刷新列表核对");
        }

        audit("CREATE", e, actor, Map.of("invoiceKind", kind,
                "sellerName", e.getSellerName(), "sellerTaxNo", e.getSellerTaxNo(),
                "invoiceNo", e.getInvoiceNo(), "amountYuan", yuan(amount),
                "netYuan", yuan(netAmount), "taxYuan", yuan(taxAmount),
                "taxRate", rate.stripTrailingZeros().toPlainString(),
                "storeCode", storeCode, "invoiceDate", invoiceDate.toString()));
        return viewOf(e);
    }

    // ==================== 状态机流转 ====================

    /**
     * 用途确认：UNCONFIRMED → CONFIRMED（DEDUCT/REFUND）；NO_DEDUCT 直接入 NON_DEDUCTIBLE 终态旁路。
     * NO_DEDUCT 必须带七码不抵扣原因。
     */
    @Transactional
    public Map<String, Object> confirm(Long id, Map<String, Object> body, String actor) {
        FinInputInvoice e = mustInput(id);
        if (!DataScope.canReadStore(e.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该门店进项发票");
        }
        if (!"UNCONFIRMED".equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅待确认（UNCONFIRMED）发票可做用途确认，当前状态：" + statusLabel(e.getStatus()));
        }
        String purpose = body == null ? null : str(body.get("purpose"));
        if (purpose == null || !CONFIRM_PURPOSES.contains(purpose)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "用途不合法（DEDUCT 抵扣 / NO_DEDUCT 不抵扣 / REFUND 退税）");
        }
        OffsetDateTime now = OffsetDateTime.now();
        String staff = actor == null ? "system" : truncate(actor, 16);
        if ("NO_DEDUCT".equals(purpose)) {
            String reason = requireReason(body);
            e.setPurpose("NO_DEDUCT");
            e.setStatus("NON_DEDUCTIBLE");
            e.setNondeductReason(reason);
            e.setConfirmer(actor == null ? "system" : truncate(actor, 64));
            e.setConfirmedAt(now);
            if (body.get("remark") != null) e.setRemark(trimTo(str(body.get("remark")), 256));
            e.setUpdatedBy(staff);
            repo.save(e);
            audit("NON_DEDUCTIBLE", e, actor, Map.of("purpose", "NO_DEDUCT", "reason", reason));
            return viewOf(e);
        }
        e.setPurpose(purpose);
        e.setStatus("CONFIRMED");
        e.setConfirmer(actor == null ? "system" : truncate(actor, 64));
        e.setConfirmedAt(now);
        e.setUpdatedBy(staff);
        repo.save(e);
        audit("CONFIRM", e, actor, Map.of("purpose", purpose));
        return viewOf(e);
    }

    /** 撤销用途确认：仅 CONFIRMED 可回 UNCONFIRMED；已抵扣/已转出/不抵扣终态一律 422。 */
    @Transactional
    public Map<String, Object> revokeConfirm(Long id, String actor) {
        FinInputInvoice e = mustInput(id);
        if (!DataScope.canReadStore(e.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该门店进项发票");
        }
        if ("DEDUCTED".equals(e.getStatus()) || "TRANSFERRED_OUT".equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "已抵扣发票不可撤销确认，请先做进项转出");
        }
        if (!"CONFIRMED".equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅已用途确认（CONFIRMED）发票可撤销确认，当前状态：" + statusLabel(e.getStatus()));
        }
        e.setStatus("UNCONFIRMED");
        e.setPurpose("PENDING");
        e.setConfirmer(null);
        e.setConfirmedAt(null);
        e.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
        repo.save(e);
        audit("REVOKE_CONFIRM", e, actor, Map.of("id", e.getId()));
        return viewOf(e);
    }

    /**
     * 抵扣：CONFIRMED 且 purpose=DEDUCT → DEDUCTED；必须归属 OPEN 申报期（不传则懒创建当前月期）。
     * 申报期一经申报即锁抵扣；REFUND 退税勾选不走本卡抵扣链路。
     */
    @Transactional
    public Map<String, Object> deduct(Long id, Map<String, Object> body, String actor) {
        FinInputInvoice e = mustInput(id);
        if (!DataScope.canReadStore(e.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该门店进项发票");
        }
        if (!"CONFIRMED".equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅已用途确认（CONFIRMED）发票可抵扣，当前状态：" + statusLabel(e.getStatus()));
        }
        if (!"DEDUCT".equals(e.getPurpose())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅抵扣用途的发票可申报抵扣，当前用途：" + purposeLabel(e.getPurpose())
                            + "；退税勾选不在本卡办理");
        }
        Long periodId = body == null ? null : parseLongOrNull(body.get("periodId"), "申报期");
        FinTaxPeriod period = taxPeriods.resolveOpenPeriodForDeduct(periodId, actor);
        e.setStatus("DEDUCTED");
        e.setPeriodId(period.getId());
        e.setDeductedAt(OffsetDateTime.now());
        e.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
        repo.save(e);
        audit("DEDUCT", e, actor, Map.of(
                "periodId", period.getId(), "period", period.getPeriod(),
                "taxYuan", yuan(e.getTaxAmount())));
        return viewOf(e);
    }

    /** 进项转出：DEDUCTED → TRANSFERRED_OUT（终态），转出额 1..taxAmount，七码原因必填。 */
    @Transactional
    public Map<String, Object> transferOut(Long id, Map<String, Object> body, String actor) {
        FinInputInvoice e = mustInput(id);
        if (!DataScope.canReadStore(e.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该门店进项发票");
        }
        if (!"DEDUCTED".equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅已抵扣（DEDUCTED）发票可做进项转出，当前状态：" + statusLabel(e.getStatus()));
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "转出内容不能为空");
        }
        long out = parsePositiveFen(body.get("amount"), "进项转出额");
        if (out < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "进项转出额须大于 0");
        }
        if (out > e.getTaxAmount()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "进项转出额不能大于票面税额（¥" + yuan(e.getTaxAmount()) + "）");
        }
        String reason = requireReason(body);
        e.setStatus("TRANSFERRED_OUT");
        e.setTransferOutAmount(out);
        e.setNondeductReason(reason);
        e.setTransferredAt(OffsetDateTime.now());
        if (body.get("remark") != null) e.setRemark(trimTo(str(body.get("remark")), 256));
        e.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
        repo.save(e);
        audit("TRANSFER_OUT", e, actor, Map.of(
                "periodId", e.getPeriodId() == null ? "" : e.getPeriodId(),
                "transferOutYuan", yuan(out), "reason", reason,
                "taxYuan", yuan(e.getTaxAmount())));
        return viewOf(e);
    }

    /** 标记不抵扣：UNCONFIRMED → NON_DEDUCTIBLE 终态旁路（edit 权限），七码原因必填。 */
    @Transactional
    public Map<String, Object> nonDeductible(Long id, Map<String, Object> body, String actor) {
        FinInputInvoice e = mustInput(id);
        if (!DataScope.canReadStore(e.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该门店进项发票");
        }
        if (!"UNCONFIRMED".equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "仅待确认（UNCONFIRMED）发票可标记不抵扣，当前状态：" + statusLabel(e.getStatus()));
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "不抵扣内容不能为空");
        }
        String reason = requireReason(body);
        e.setPurpose("NO_DEDUCT");
        e.setStatus("NON_DEDUCTIBLE");
        e.setNondeductReason(reason);
        e.setConfirmer(actor == null ? "system" : truncate(actor, 64));
        e.setConfirmedAt(OffsetDateTime.now());
        if (body.get("remark") != null) e.setRemark(trimTo(str(body.get("remark")), 256));
        e.setUpdatedBy(actor == null ? "system" : truncate(actor, 16));
        repo.save(e);
        audit("NON_DEDUCTIBLE", e, actor, Map.of("reason", reason));
        return viewOf(e);
    }

    // ==================== 视图与工具 ====================

    private FinInputInvoice mustInput(Long id) {
        return repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "进项发票不存在：" + id));
    }

    private Map<String, Object> viewOf(FinInputInvoice e) {
        return invoiceView(e, aggregation.resolveStoreNames(List.of(e.getStoreCode())));
    }

    private FinTaxPeriod resolvePeriodForQuery(Long periodId, String type, String label) {
        if (periodId != null) {
            return taxPeriods.mustPeriod(periodId);
        }
        String t = normalizeType(type);
        String l;
        if (label == null || label.isBlank()) {
            Optional<FinTaxPeriod> open =
                    taxPeriods.findOpenByDay(t, LocalDate.now(CN));
            return open.orElse(null);
        }
        l = label.trim();
        boolean ok = "QUARTER".equals(t) ? QUARTER_LABEL.matcher(l).matches() : MONTH_LABEL.matcher(l).matches();
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "QUARTER".equals(t) ? "季度期间标识格式须为 yyyy-Qn（如 2026-Q3）"
                            : "月度期间标识格式须为 yyyy-MM（如 2026-08）");
        }
        return taxPeriods.findOpen(t, l).orElse(null);
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "MONTH";
        String t = type.trim().toUpperCase();
        if (!"MONTH".equals(t) && !"QUARTER".equals(t)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "申报频率不合法（MONTH/QUARTER）");
        }
        return t;
    }

    private String requireReason(Map<String, Object> body) {
        String reason = str(body.get("reason"));
        if (reason == null || !REASONS.contains(reason.trim())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "不抵扣/转出原因不合法（须为七码之一：WELFARE/LOSS_GOODS/LOSS_PRODUCT/"
                            + "LOSS_REAL_ESTATE/LOSS_CONSTRUCTION/LOAN_DAILY/OTHER）");
        }
        return reason.trim();
    }

    private Map<String, Object> invoiceView(FinInputInvoice e, Map<String, String> storeNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("registerNo", e.getRegisterNo());
        m.put("invoiceCode", e.getInvoiceCode() == null ? "" : e.getInvoiceCode());
        m.put("invoiceNo", e.getInvoiceNo());
        m.put("invoiceKind", e.getInvoiceKind());
        m.put("invoiceKindLabel", KIND_LABEL.getOrDefault(e.getInvoiceKind(), e.getInvoiceKind()));
        m.put("sellerName", e.getSellerName());
        m.put("sellerTaxNo", e.getSellerTaxNo());
        m.put("supplierId", e.getSupplierId());
        m.put("amount", yuan(e.getAmount()));
        m.put("netAmount", yuan(e.getNetAmount()));
        m.put("taxAmount", yuan(e.getTaxAmount()));
        m.put("taxRate", e.getTaxRate());
        m.put("category", e.getCategory());
        m.put("categoryLabel", CATEGORY_LABEL.getOrDefault(e.getCategory(), e.getCategory()));
        m.put("purpose", e.getPurpose());
        m.put("purposeLabel", purposeLabel(e.getPurpose()));
        m.put("status", e.getStatus());
        m.put("statusLabel", statusLabel(e.getStatus()));
        m.put("nondeductReason", e.getNondeductReason() == null ? "" : e.getNondeductReason());
        m.put("nondeductReasonLabel", e.getNondeductReason() == null ? ""
                : REASON_LABEL.getOrDefault(e.getNondeductReason(), e.getNondeductReason()));
        m.put("transferOutAmount", yuan(e.getTransferOutAmount()));
        m.put("periodId", e.getPeriodId());
        m.put("invoiceDate", e.getInvoiceDate());
        m.put("ageDays", java.time.temporal.ChronoUnit.DAYS.between(e.getInvoiceDate(), LocalDate.now(CN)));
        m.put("confirmedAt", e.getConfirmedAt());
        m.put("deductedAt", e.getDeductedAt());
        m.put("transferredAt", e.getTransferredAt());
        m.put("storeCode", e.getStoreCode());
        m.put("store", storeNames.getOrDefault(e.getStoreCode(), e.getStoreCode()));
        m.put("operator", e.getOperator());
        m.put("confirmer", e.getConfirmer() == null ? "" : e.getConfirmer());
        m.put("remark", e.getRemark() == null ? "" : e.getRemark());
        m.put("createdAt", e.getCreatedAt());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }

    /** 登记号 PINV-yyyyMMdd(北京)-0001，序号按当日已有号递增（synchronized 防并发重号）。 */
    private synchronized String nextRegisterNo() {
        String day = LocalDate.now(CN).format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "PINV-" + day + "-";
        int seq = repo.maxSeqOfDay(prefix + "%") + 1;
        return prefix + String.format("%04d", seq);
    }

    /** 税额 = 价税合计 - 价税合计 /（1+税率），四舍五入到分；税率 0 时税额 0。 */
    private long computeTaxFen(long amountFen, BigDecimal rate) {
        if (rate.signum() == 0) return 0L;
        BigDecimal divisor = BigDecimal.ONE.add(rate);
        BigDecimal net = new BigDecimal(amountFen).divide(divisor, 0, RoundingMode.HALF_UP);
        return amountFen - net.longValue();
    }

    private BigDecimal parseInvoiceRate(Object v) {
        if (v == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "税率不能为空");
        BigDecimal r;
        try {
            r = new BigDecimal(String.valueOf(v).trim()).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "税率格式不合法：" + v);
        }
        if (!INVOICE_RATES.contains(r)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "税率须为允许档位（0 / 0.01 / 0.03 / 0.06 / 0.13）");
        }
        return r;
    }

    private long parsePositiveFen(Object v, String label) {
        if (v == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为空");
        BigDecimal yuan;
        try {
            yuan = new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "格式不合法：" + v);
        }
        if (yuan.signum() < 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为负");
        return yuan.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private Long parseLongOrNull(Object v, String label) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "标识不合法：" + v);
        }
    }

    private LocalDate parseDate(Object v, String label) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, label + "不能为空");
        }
        try {
            return LocalDate.parse(String.valueOf(v).trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    label + "日期格式不合法（须为 yyyy-MM-dd）：" + v);
        }
    }

    private void audit(String action, FinInputInvoice e, String actor, Object payload) {
        try {
            audit.record("FIN_INPUT_INVOICE", e.getRegisterNo(), actor == null ? "system" : actor,
                    action, objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            log.error("审计序列化失败 txnNo={} action={} : {}", e.getRegisterNo(), action, ex.getMessage());
        }
    }

    private static double yuan(Long fen) {
        if (fen == null) return 0.0;
        return new BigDecimal(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String statusLabel(String status) {
        return STATUS_LABEL.getOrDefault(status, status);
    }

    private static String purposeLabel(String purpose) {
        return PURPOSE_LABEL.getOrDefault(purpose, purpose);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
