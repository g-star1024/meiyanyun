package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 咨询方案单业务（M4-09 咨询 → 医生二次审核 → 签病历生成缴费单）。
 *
 * <p>状态机对齐前端 consultation mock store（活规格）：
 * <pre>
 *   submit → PENDING_REVIEW →(approve)→ APPROVED →(signEmr 自动开单)→ READY_PAY
 *                          →(reject)→ REJECTED（改单重提）
 *   doctorEdit：PENDING_REVIEW → APPROVED（医生改单即通过）
 *   收款后由订单 pay 联动 markPaidByOrder：READY_PAY → PAID
 * </pre>
 * 订单生成时机：医生签首程病历后系统自动建「待收款」缴费单（诊疗主线）；
 * 零售支线（/prescription）走 createRetailOrder 直接建「待收款」订单，不关联方案单、不走医生审核。
 * 金额单位「分」；全部写动作落 audit_log + 方案单内联留痕。
 */
@Service
public class ConsultPlanService {

    private final PlanRepository planRepo;
    private final PlanItemRepository itemRepo;
    private final PlanRevisionRepository revRepo;
    private final TxnOrderRepository orderRepo;
    private final OrderItemRepository orderItemRepo;
    private final OrderNoGenerator orderNoGen;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;
    private final ObjectMapper json = new ObjectMapper();

    public ConsultPlanService(PlanRepository planRepo, PlanItemRepository itemRepo,
                              PlanRevisionRepository revRepo, TxnOrderRepository orderRepo,
                              OrderItemRepository orderItemRepo, OrderNoGenerator orderNoGen,
                              AuditRecorder audit, ApptRefNameResolver names) {
        this.planRepo = planRepo;
        this.itemRepo = itemRepo;
        this.revRepo = revRepo;
        this.orderRepo = orderRepo;
        this.orderItemRepo = orderItemRepo;
        this.orderNoGen = orderNoGen;
        this.audit = audit;
        this.names = names;
    }

    // ==================== DTO ====================

    public record PlanItemCmd(String itemCode, String itemName, String spec,
                              Integer qty, Long unitPrice, String riskTags) {}

    public record ContraCmd(Boolean pregnant, Boolean allergy, Boolean scarConstitution,
                            Boolean skinLesion, Boolean coagulationAbn, Boolean seriousIllness, String note) {}

    public record SubmitCmd(String customerId, String storeCode, String consultantId, String doctorId,
                            String conclusion, List<PlanItemCmd> items, ContraCmd contraindications,
                            Boolean consentConsultant, Boolean consentCustomer,
                            String consentSignatureDataUrl, String consentSignerName, String consentDocVersion,
                            String skinReportId, String operator) {}

    public record ReviewCmd(String operator, String note) {}

    public record DoctorEditCmd(String operator, String conclusion, List<PlanItemCmd> items, String reason) {}

    public record SignEmrCmd(String operator, String customerName, String chiefComplaint,
                             String presentIllness, String pastHistory, String diagnosis,
                             String treatment, String prescription, String note) {}

    public record RetailCmd(String customerId, String storeCode, String consultant,
                            String project, List<M4FlowController.OrderItemCmd> items, String operator) {}

    public record PlanItemView(String itemCode, String itemName, String spec, Integer qty,
                               Long unitPrice, Long amount, String riskTags) {}
    public record RevisionView(Long revId, String kind, String actorId, String actorName,
                               String reason, OffsetDateTime at) {}
    public record PlanView(String planId, String customerId, String customerName,
                           String storeCode, String storeName,
                           String consultantId, String consultantName,
                           String doctorId, String doctorName, String status,
                           String conclusion, Long planAmount, Long planCost,
                           Object contraindications,
                           Boolean consentConsultant, Boolean consentCustomer,
                           String consentSignerName, String consentDocVersion,
                           OffsetDateTime consentAt, String skinReportId,
                           OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                           String reviewedByName, String rejectReason,
                           String emrId, OffsetDateTime emrSignedAt,
                           String orderNo, String orderStatus, OffsetDateTime paidAt,
                           OffsetDateTime createdAt,
                           List<PlanItemView> items, List<RevisionView> revisions) {}

    // ==================== 咨询师：提交方案审核 ====================

    @Transactional
    public PlanView submit(SubmitCmd cmd) {
        // 外键存在性（服务间调用）
        if (blank(cmd.customerId())) throw bad("客户不能为空");
        if (!names.customerNames(List.of(cmd.customerId())).containsKey(cmd.customerId())) {
            throw bad("客户不存在: " + cmd.customerId());
        }
        if (blank(cmd.storeCode()) || !names.storeNames(List.of(cmd.storeCode())).containsKey(cmd.storeCode())) {
            throw bad("门店不存在或未指定: " + cmd.storeCode());
        }
        if (blank(cmd.doctorId()) || !names.staffNames(List.of(cmd.doctorId())).containsKey(cmd.doctorId())) {
            throw bad("请指定有效的审核医生");
        }
        if (blank(cmd.conclusion())) {
            throw bad("咨询结论 / 诊断为必填项（将作为病历诊断）");
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw bad("方案明细为空，请先添加项目");
        }
        if (!Boolean.TRUE.equals(cmd.consentConsultant()) || !Boolean.TRUE.equals(cmd.consentCustomer())) {
            throw bad("知情同意双确认未完成");
        }
        if (blank(cmd.consentSignatureDataUrl()) || blank(cmd.consentSignerName())) {
            throw bad("客户未完成《知情同意书》手写电子签名");
        }
        // 禁忌阳性须填处置说明
        ContraCmd c = cmd.contraindications();
        boolean positive = c != null && (Boolean.TRUE.equals(c.pregnant()) || Boolean.TRUE.equals(c.allergy())
                || Boolean.TRUE.equals(c.scarConstitution()) || Boolean.TRUE.equals(c.skinLesion())
                || Boolean.TRUE.equals(c.coagulationAbn()) || Boolean.TRUE.equals(c.seriousIllness()));
        if (positive && (c == null || blank(c.note()))) {
            throw bad("存在禁忌阳性项，必须填写医生备注 / 处置说明");
        }

        long total = 0L;
        List<PlanItem> entities = new ArrayList<>();
        int line = 1;
        for (PlanItemCmd it : cmd.items()) {
            if (blank(it.itemName())) throw bad("第 " + line + " 行项目名称不能为空");
            int qty = it.qty() == null || it.qty() < 1 ? 1 : it.qty();
            long price = it.unitPrice() == null || it.unitPrice() < 0 ? 0L : it.unitPrice();
            long sub = price * qty;
            total += sub;
            PlanItem pi = new PlanItem();
            pi.setLineNo(line);
            pi.setItemCode(it.itemCode());
            pi.setItemName(it.itemName());
            pi.setSpec(it.spec());
            pi.setQty(qty);
            pi.setUnitPrice(price);
            pi.setAmount(sub);
            pi.setRiskTags(it.riskTags());
            entities.add(pi);
            line++;
        }

        ConsultPlan p = new ConsultPlan();
        p.setPlanId(nextPlanNo());
        p.setCustomerId(cmd.customerId());
        p.setStoreCode(cmd.storeCode());
        p.setConsultantId(cmd.consultantId());
        p.setDoctorId(cmd.doctorId());
        p.setStatus("PENDING_REVIEW");
        p.setConclusion(cmd.conclusion());
        p.setPlanAmount(total);
        p.setPlanCost(Math.round(total * 0.35));
        p.setContraindicationsJson(toJson(c));
        p.setConsentConsultant(Boolean.TRUE.equals(cmd.consentConsultant()));
        p.setConsentCustomer(Boolean.TRUE.equals(cmd.consentCustomer()));
        p.setConsentSignatureDataUrl(cmd.consentSignatureDataUrl());
        p.setConsentSignerName(cmd.consentSignerName());
        p.setConsentDocVersion(blank(cmd.consentDocVersion()) ? "MEIYUN-ICF-v2026.1" : cmd.consentDocVersion());
        p.setConsentAt(OffsetDateTime.now());
        p.setSkinReportId(cmd.skinReportId());
        p.setSubmittedAt(OffsetDateTime.now());
        planRepo.save(p);

        for (PlanItem pi : entities) { pi.setPlanId(p.getPlanId()); itemRepo.save(pi); }

        addRevision(p.getPlanId(), "SUBMIT", DataScope.currentActor(), "方案已与客户沟通确认，提交医生审核", null);
        audit.record("PLAN", p.getPlanId(), actor(cmd.operator()), "SUBMIT",
                "{\"customer\":\"" + p.getCustomerId() + "\",\"amount\":" + total
                        + ",\"items\":" + entities.size() + ",\"doctor\":\"" + p.getDoctorId() + "\"}");
        return get(p.getPlanId());
    }

    // ==================== 医生：审核通过 / 驳回 / 改单 ====================

    @Transactional
    public PlanView approve(String planId, ReviewCmd cmd) {
        ConsultPlan p = requirePlan(planId);
        if (!"PENDING_REVIEW".equals(p.getStatus())) {
            throw bad("仅「待医生审核」的方案单可审核通过，当前: " + p.getStatus());
        }
        p.setStatus("APPROVED");
        p.setReviewedBy(DataScope.currentActor());
        p.setReviewedAt(OffsetDateTime.now());
        planRepo.save(p);
        addRevision(planId, "APPROVE", DataScope.currentActor(),
                blank(cmd.note()) ? "审核通过，适应症与禁忌核验无误" : cmd.note(), null);
        audit.record("PLAN", planId, actor(cmd.operator()), "APPROVE", "{}");
        return get(planId);
    }

    @Transactional
    public PlanView reject(String planId, ReviewCmd cmd) {
        ConsultPlan p = requirePlan(planId);
        if (!"PENDING_REVIEW".equals(p.getStatus())) {
            throw bad("仅「待医生审核」的方案单可驳回，当前: " + p.getStatus());
        }
        if (cmd == null || blank(cmd.note())) {
            throw bad("驳回必须填写原因（咨询师将据此改单重提）");
        }
        p.setStatus("REJECTED");
        p.setReviewedBy(DataScope.currentActor());
        p.setReviewedAt(OffsetDateTime.now());
        p.setRejectReason(cmd.note().trim());
        planRepo.save(p);
        addRevision(planId, "REJECT", DataScope.currentActor(), cmd.note().trim(), null);
        audit.record("PLAN", planId, actor(cmd.operator()), "REJECT",
                "{\"reason\":\"" + esc(cmd.note().trim()) + "\"}");
        return get(planId);
    }

    @Transactional
    public PlanView doctorEdit(String planId, DoctorEditCmd cmd) {
        ConsultPlan p = requirePlan(planId);
        if (!"PENDING_REVIEW".equals(p.getStatus())) {
            throw bad("仅「待医生审核」的方案单可由医生改单，当前: " + p.getStatus());
        }
        if (cmd == null || blank(cmd.reason())) {
            throw bad("医生改单必须填写改单说明（将留痕，咨询师可见）");
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw bad("方案明细为空");
        }
        long oldAmount = p.getPlanAmount() == null ? 0L : p.getPlanAmount();
        if (!blank(cmd.conclusion())) p.setConclusion(cmd.conclusion());

        // 重建子项
        itemRepo.deleteByPlanId(planId);
        long total = 0L;
        int line = 1;
        List<PlanItem> entities = new ArrayList<>();
        for (PlanItemCmd it : cmd.items()) {
            if (blank(it.itemName())) throw bad("第 " + line + " 行项目名称不能为空");
            int qty = it.qty() == null || it.qty() < 1 ? 1 : it.qty();
            long price = it.unitPrice() == null || it.unitPrice() < 0 ? 0L : it.unitPrice();
            long sub = price * qty;
            total += sub;
            PlanItem pi = new PlanItem();
            pi.setPlanId(planId);
            pi.setLineNo(line);
            pi.setItemCode(it.itemCode());
            pi.setItemName(it.itemName());
            pi.setSpec(it.spec());
            pi.setQty(qty);
            pi.setUnitPrice(price);
            pi.setAmount(sub);
            pi.setRiskTags(it.riskTags());
            entities.add(pi);
            line++;
        }
        itemRepo.saveAll(entities);
        p.setPlanAmount(total);
        p.setPlanCost(Math.round(total * 0.35));
        p.setStatus("APPROVED");
        p.setReviewedBy(DataScope.currentActor());
        p.setReviewedAt(OffsetDateTime.now());
        planRepo.save(p);

        String changeNote = "医生改单并通过：¥" + oldAmount + " → ¥" + total + "（分）；" + cmd.reason().trim();
        addRevision(planId, "DOCTOR_EDIT", DataScope.currentActor(), changeNote, null);
        audit.record("PLAN", planId, actor(cmd.operator()), "DOCTOR_EDIT",
                "{\"from\":" + oldAmount + ",\"to\":" + total + "}");
        return get(planId);
    }

    // ==================== 医生：签首程病历 → 自动生成缴费单 ====================

    @Transactional
    public M4FlowController.OrderView signEmr(String planId, SignEmrCmd cmd) {
        ConsultPlan p = requirePlan(planId);
        // 幂等优先：已生成缴费单（READY_PAY/PAID）的重复签署/网络重试，直接返回原单，不重复开单、不报错。
        if (!blank(p.getOrderNo())) {
            return orderView(p.getOrderNo());
        }
        if (!"PENDING_REVIEW".equals(p.getStatus()) && !"APPROVED".equals(p.getStatus())) {
            throw bad("仅「待审核 / 审核通过待写病历」的方案单可签病历，当前: " + p.getStatus());
        }
        List<PlanItem> items = itemRepo.findByPlanIdOrderByLineNoAsc(planId);
        if (items.isEmpty()) throw bad("方案明细为空，无法生成病历与缴费单");

        // 诊断必填：取医生填写，否则取咨询结论
        String diagnosis = cmd != null && !blank(cmd.diagnosis()) ? cmd.diagnosis().trim()
                : (blank(p.getConclusion()) ? null : p.getConclusion().trim());
        if (blank(diagnosis)) {
            throw bad("诊断与治疗方案为必填项：请在「诊断 / 皮肤评估」补填（咨询结论为空），无需退回咨询环节");
        }

        // 待审核单先落审核通过
        if ("PENDING_REVIEW".equals(p.getStatus())) {
            p.setStatus("APPROVED");
            p.setReviewedBy(DataScope.currentActor());
            p.setReviewedAt(OffsetDateTime.now());
            addRevision(planId, "APPROVE", DataScope.currentActor(),
                    "审核通过（与首程病历一并签署）", null);
        }

        // 自动生成缴费单（待收款）。诊疗订单经医生审核+病历，医疗合规已完成，直接待收款；
        // 涉钱双签（M4-X）留给现金收款/退款环节，不在此重复。
        List<M4FlowController.OrderItemCmd> orderItems = items.stream()
                .map(pi -> new M4FlowController.OrderItemCmd(pi.getItemName(), pi.getQty(), pi.getUnitPrice()))
                .toList();
        String project = items.get(0).getItemName();
        TxnOrder order = buildPendingOrder(p.getCustomerId(), p.getStoreCode(), p.getConsultantId(),
                project, orderItems, contraLevel(p), contraDetail(p));
        orderRepo.save(order);
        int ln = 1;
        for (PlanItem pi : items) {
            OrderItem oi = new OrderItem();
            oi.setOrderNo(order.getOrderNo());
            oi.setLineNo(ln++);
            oi.setItemName(pi.getItemName());
            oi.setQty(pi.getQty());
            oi.setUnitPrice(pi.getUnitPrice());
            oi.setAmount(pi.getAmount());
            orderItemRepo.save(oi);
        }

        p.setOrderNo(order.getOrderNo());
        p.setEmrId(nextEmrNo());
        p.setEmrSignedAt(OffsetDateTime.now());
        p.setStatus("READY_PAY");
        planRepo.save(p);

        addRevision(planId, "EMR_SIGN", DataScope.currentActor(),
                "首程病历已签署，缴费单 " + order.getOrderNo() + " 已生成待支付", null);
        audit.record("PLAN", planId, actor(cmd == null ? null : cmd.operator()), "EMR_SIGN",
                "{\"order\":\"" + order.getOrderNo() + "\",\"amount\":" + order.getAmount() + "}");
        audit.record("ORDER", order.getOrderNo(), actor(cmd == null ? null : cmd.operator()), "CREATE",
                "{\"source\":\"PLAN\",\"plan\":\"" + planId + "\",\"project\":\"" + esc(project)
                        + "\",\"amount\":" + order.getAmount() + ",\"items\":" + items.size() + "}");
        return orderView(order.getOrderNo());
    }

    // ==================== 零售支线：现场直开缴费单（不走医生审核） ====================

    @Transactional
    public M4FlowController.OrderView createRetailOrder(RetailCmd cmd) {
        if (blank(cmd.customerId()) || !names.customerNames(List.of(cmd.customerId())).containsKey(cmd.customerId())) {
            throw bad("客户不存在或未选择: " + cmd.customerId()
                    + "（散客也需是建档客户；未建档请先建档再开单）");
        }
        if (blank(cmd.storeCode()) || !names.storeNames(List.of(cmd.storeCode())).containsKey(cmd.storeCode())) {
            throw bad("门店不存在或未指定: " + cmd.storeCode());
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw bad("开单明细为空，请先选择商品 / 项目");
        }
        String project = blank(cmd.project())
                ? cmd.items().stream().findFirst().map(M4FlowController.OrderItemCmd::itemName).orElse("零售")
                : cmd.project();
        TxnOrder order = buildPendingOrder(cmd.customerId(), cmd.storeCode(), cmd.consultant(),
                project, cmd.items(), "GREEN", null);
        orderRepo.save(order);
        int ln = 1;
        long total = 0L;
        for (M4FlowController.OrderItemCmd it : cmd.items()) {
            int qty = it.qty() == null || it.qty() < 1 ? 1 : it.qty();
            long price = it.unitPrice() == null || it.unitPrice() < 0 ? 0L : it.unitPrice();
            long sub = price * qty;
            total += sub;
            OrderItem oi = new OrderItem();
            oi.setOrderNo(order.getOrderNo());
            oi.setLineNo(ln++);
            oi.setItemName(it.itemName());
            oi.setQty(qty);
            oi.setUnitPrice(price);
            oi.setAmount(sub);
            orderItemRepo.save(oi);
        }
        audit.record("ORDER", order.getOrderNo(), actor(cmd.operator()), "CREATE",
                "{\"source\":\"RETAIL\",\"project\":\"" + esc(project) + "\",\"amount\":" + total
                        + ",\"items\":" + cmd.items().size() + "}");
        return orderView(order.getOrderNo());
    }

    // ==================== 收款联动：READY_PAY → PAID ====================

    @Transactional
    public void markPaidByOrder(String orderNo, String operator) {
        // 留痕操作人收敛 JWT（收款联动场景 operator 为调用方透传，不可信）
        final String actor = DataScope.currentActor();
        planRepo.findByOrderNo(orderNo).ifPresent(p -> {
            if ("READY_PAY".equals(p.getStatus())) {
                p.setStatus("PAID");
                p.setPaidAt(OffsetDateTime.now());
                planRepo.save(p);
                addRevision(p.getPlanId(), "PAY", actor, "缴费单收款完成，方案解锁治疗", null);
                audit.record("PLAN", p.getPlanId(), actor, "PAY",
                        "{\"order\":\"" + orderNo + "\"}");
            }
        });
    }

    // ==================== 读模型 ====================

    /**
     * 方案单队列：数据域强制注入——SELF 咨询师/医生只见本人参与的方案单，
     * STORE 本店、REGION 本区、GROUP 全量；status/storeCode 为可选叠加过滤。
     */
    @Transactional(readOnly = true)
    public Page<PlanView> queue(String status, String storeCode, Pageable pageable) {
        Specification<ConsultPlan> spec = planScopeSpec();
        if (!blank(status)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (!blank(storeCode)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        Page<ConsultPlan> page = planRepo.findAll(spec,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Order.desc("createdAt"))));
        return page.map(this::toView);
    }

    /**
     * 方案单数据域：门店域谓词在外层 AND（SELF/STORE/REGION 均先限门店范围）；
     * SELF 域归属人为「咨询师或医生为本人」（OR 在内层，不突破门店范围）。
     */
    private Specification<ConsultPlan> planScopeSpec() {
        var user = DataScope.current();
        Specification<ConsultPlan> spec = DataScope.storeSpec("storeCode");
        if (user != null && !user.isSuper() && DataScope.SCOPE_SELF.equals(user.scope())
                && user.staffId() != null) {
            String me = user.staffId();
            Specification<ConsultPlan> owner = (root, q, cb) -> cb.or(
                    cb.equal(root.get("consultantId"), me),
                    cb.equal(root.get("doctorId"), me));
            spec = spec.and(owner);
        }
        return spec;
    }

    @Transactional(readOnly = true)
    public PlanView get(String planId) {
        return toView(requirePlan(planId));
    }

    private PlanView toView(ConsultPlan p) {
        Map<String, String> cust = names.customerNames(List.of(p.getCustomerId()));
        Map<String, String> stores = blank(p.getStoreCode()) ? Map.of() : names.storeNames(List.of(p.getStoreCode()));
        Map<String, String> staff = new LinkedHashMap<>();
        if (!blank(p.getConsultantId())) staff.putAll(names.staffNames(List.of(p.getConsultantId())));
        if (!blank(p.getDoctorId())) staff.putAll(names.staffNames(List.of(p.getDoctorId())));
        if (!blank(p.getReviewedBy())) staff.putAll(names.staffNames(List.of(p.getReviewedBy())));

        List<PlanItem> items = itemRepo.findByPlanIdOrderByLineNoAsc(p.getPlanId());
        List<PlanItemView> itemViews = items.stream()
                .map(pi -> new PlanItemView(pi.getItemCode(), pi.getItemName(), pi.getSpec(),
                        pi.getQty(), pi.getUnitPrice(), pi.getAmount(), pi.getRiskTags()))
                .toList();
        List<PlanRevision> revs = revRepo.findByPlanIdOrderByRevIdAsc(p.getPlanId());
        List<RevisionView> revViews = revs.stream()
                .map(r -> new RevisionView(r.getRevId(), r.getKind(), r.getActorId(),
                        r.getActorName(), r.getReason(), r.getCreatedAt()))
                .toList();
        String orderStatus = null;
        if (!blank(p.getOrderNo())) {
            orderStatus = orderRepo.findById(p.getOrderNo()).map(TxnOrder::getStatus).orElse(null);
        }
        return new PlanView(
                p.getPlanId(), p.getCustomerId(), cust.get(p.getCustomerId()),
                p.getStoreCode(), blank(p.getStoreCode()) ? null : stores.get(p.getStoreCode()),
                p.getConsultantId(), blank(p.getConsultantId()) ? null : staff.get(p.getConsultantId()),
                p.getDoctorId(), blank(p.getDoctorId()) ? null : staff.get(p.getDoctorId()),
                p.getStatus(), p.getConclusion(), p.getPlanAmount(), p.getPlanCost(),
                parseJson(p.getContraindicationsJson()),
                p.getConsentConsultant(), p.getConsentCustomer(),
                p.getConsentSignerName(), p.getConsentDocVersion(),
                p.getConsentAt(), p.getSkinReportId(),
                p.getSubmittedAt(), p.getReviewedAt(),
                blank(p.getReviewedBy()) ? p.getReviewedByName() : staff.get(p.getReviewedBy()),
                p.getRejectReason(), p.getEmrId(), p.getEmrSignedAt(),
                p.getOrderNo(), orderStatus, p.getPaidAt(), p.getCreatedAt(),
                itemViews, revViews);
    }

    // ==================== 内部 ====================

    private TxnOrder buildPendingOrder(String customerId, String storeCode, String consultant,
                                       String project, List<M4FlowController.OrderItemCmd> items,
                                       String contraCheck, String contraDetail) {
        long total = 0L;
        for (M4FlowController.OrderItemCmd it : items) {
            int qty = it.qty() == null || it.qty() < 1 ? 1 : it.qty();
            long price = it.unitPrice() == null || it.unitPrice() < 0 ? 0L : it.unitPrice();
            total += price * qty;
        }
        TxnOrder o = new TxnOrder();
        o.setOrderNo(orderNoGen.nextOrderNo());
        o.setCustomerId(customerId);
        o.setStoreCode(storeCode);
        o.setProject(project == null ? "零售" : project);
        o.setAmount(total);
        o.setConsultant(consultant);
        o.setContraCheck(contraCheck == null ? "GREEN" : contraCheck);
        o.setContraDetail(contraDetail);
        o.setStatus("待收款");
        return o;
    }

    private M4FlowController.OrderView orderView(String orderNo) {
        TxnOrder o = orderRepo.findById(orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + orderNo));
        List<OrderItem> items = orderItemRepo.findByOrderNoIn(List.of(orderNo));
        Map<String, String> cust = names.customerNames(List.of(o.getCustomerId()));
        Map<String, String> phones = names.customerPhones(List.of(o.getCustomerId()));
        Map<String, String> stores = blank(o.getStoreCode()) ? Map.of() : names.storeNames(List.of(o.getStoreCode()));
        Map<String, String> consultants = blank(o.getConsultant()) ? Map.of() : names.staffNames(List.of(o.getConsultant()));
        List<CustomerViewController.OrderItemView> iv = items.stream()
                .map(it -> new CustomerViewController.OrderItemView(it.getItemName(), it.getQty(), it.getUnitPrice(), it.getAmount()))
                .toList();
        return new M4FlowController.OrderView(o.getOrderNo(), o.getCustomerId(), cust.get(o.getCustomerId()), phones.get(o.getCustomerId()),
                o.getStoreCode(), blank(o.getStoreCode()) ? null : stores.get(o.getStoreCode()),
                o.getProject(), o.getAmount(), o.getStatus(),
                blank(o.getConsultant()) ? null : consultants.get(o.getConsultant()),
                o.getContraCheck(), o.getCreatedAt(), iv,
                0L, List.of());
    }

    private String contraLevel(ConsultPlan p) {
        Object c = parseJson(p.getContraindicationsJson());
        if (c instanceof Map<?, ?> m) {
            boolean positive = m.entrySet().stream()
                    .anyMatch(e -> !"note".equals(e.getKey()) && Boolean.TRUE.equals(e.getValue()));
            return positive ? "YELLOW" : "GREEN";
        }
        return "GREEN";
    }

    private String contraDetail(ConsultPlan p) {
        Object c = parseJson(p.getContraindicationsJson());
        if (c instanceof Map<?, ?> m) {
            List<String> hits = new ArrayList<>();
            m.forEach((k, v) -> { if (!"note".equals(k) && Boolean.TRUE.equals(v)) hits.add(String.valueOf(k)); });
            return hits.isEmpty() ? null : "面诊禁忌阳性: " + String.join(",", hits);
        }
        return null;
    }

    /** 方案修订留痕：操作人一律取 JWT 登录人工号（入参 actor 为请求体透传，不可信，忽略）。 */
    private void addRevision(String planId, String kind, String actor, String reason, String changesJson) {
        PlanRevision r = new PlanRevision();
        r.setPlanId(planId);
        r.setKind(kind);
        String who = DataScope.currentActor();
        r.setActorId(who);
        r.setActorName(who);
        r.setReason(reason);
        r.setChangesJson(changesJson);
        revRepo.save(r);
    }

    /** 方案单读取 + 数据域校验（门店域为前提；SELF 域须咨询师或医生为本人），越权统一 404。 */
    private ConsultPlan requirePlan(String id) {
        ConsultPlan p = planRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(p.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        var user = DataScope.current();
        if (user != null && !user.isSuper() && DataScope.SCOPE_SELF.equals(user.scope())
                && user.staffId() != null) {
            boolean mine = user.staffId().equals(p.getConsultantId())
                    || user.staffId().equals(p.getDoctorId());
            if (!mine) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
            }
        }
        return p;
    }

    private synchronized String nextPlanNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long seq = planRepo.maxSeqOfDay("CP" + day + "-%") + 1;
        return "CP" + day + "-" + String.format("%06d", seq);
    }

    private synchronized String nextEmrNo() {
        String day = LocalDate.now().toString().replace("-", "");
        return "EM" + day + "-" + String.format("%06d",
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 1_000_000).incrementAndGet() % 1_000_000);
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try { return json.writeValueAsString(o); } catch (Exception e) { return null; }
    }

    private Object parseJson(String s) {
        if (blank(s)) return null;
        try { return json.readValue(s, Object.class); } catch (Exception e) { return null; }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String esc(String s) { return s == null ? "" : s.replace("\"", "'").replace("\\", "/"); }
    /** 审计/修订留痕操作人：一律取 JWT 登录人工号（请求体 operator 字段不可信，忽略）；无上下文回落 system。 */
    private static String actor(String a) { return DataScope.currentActor(); }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
