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
    private final StoreCatalogClient catalogClient;
    private final ObjectMapper json = new ObjectMapper();

    public ConsultPlanService(PlanRepository planRepo, PlanItemRepository itemRepo,
                              PlanRevisionRepository revRepo, TxnOrderRepository orderRepo,
                              OrderItemRepository orderItemRepo, OrderNoGenerator orderNoGen,
                              AuditRecorder audit, ApptRefNameResolver names,
                              StoreCatalogClient catalogClient) {
        this.planRepo = planRepo;
        this.itemRepo = itemRepo;
        this.revRepo = revRepo;
        this.orderRepo = orderRepo;
        this.orderItemRepo = orderItemRepo;
        this.orderNoGen = orderNoGen;
        this.audit = audit;
        this.names = names;
        this.catalogClient = catalogClient;
    }

    // ==================== DTO ====================

    public record PlanItemCmd(String itemCode, String itemName, String spec,
                              Integer qty, Long unitPrice, String riskTags) {}

    public record ContraCmd(Boolean pregnant, Boolean allergy, Boolean scarConstitution,
                            Boolean skinLesion, Boolean coagulationAbn, Boolean seriousIllness, String note) {}

    public record SubmitCmd(String planId, String arrivalId,
                            String customerId, String storeCode, String consultantId, String doctorId,
                            String conclusion, List<PlanItemCmd> items, ContraCmd contraindications,
                            Boolean consentConsultant, Boolean consentCustomer,
                            String consentSignatureDataUrl, String consentSignerName, String consentDocVersion,
                            String skinReportId, String operator) {}

    /**
     * 保存草稿入参（PENDING/ACTIVE/REJECTED 可反复保存）：planId 为空=新建 PENDING 草稿（出 CP 单号）；
     * planId 非空=更新既有草稿。草稿不做提交强校验（允许空结论/空项目/未签名），仅校验客户/门店存在性。
     */
    public record SaveDraftCmd(String planId, String arrivalId,
                               String customerId, String storeCode, String consultantId, String doctorId,
                               String conclusion, List<PlanItemCmd> items, ContraCmd contraindications,
                               Boolean consentConsultant, Boolean consentCustomer,
                               String consentSignatureDataUrl, String consentSignerName, String consentDocVersion,
                               String skinReportId, String operator) {}

    /** 接诊入参（均可空）：operator 仅审计兜底（实际取 JWT），arrivalId 关联到店登记。 */
    public record StartCmd(String operator, String arrivalId) {}

    public record ReviewCmd(String operator, String note) {}

    public record DoctorEditCmd(String operator, String conclusion, List<PlanItemCmd> items, String reason) {}

    public record SignEmrCmd(String operator, String customerName, String chiefComplaint,
                             String presentIllness, String pastHistory, String diagnosis,
                             String treatment, String prescription, String note) {}

    /** 术前四项核对（开始治疗）：知情同意/禁忌复核/药品耗材/治疗部位四项必须全部确认。 */
    public record TreatStartCmd(String operator, Boolean consentChecked, Boolean contraChecked,
                                Boolean drugChecked, Boolean siteChecked, String room, String note) {}

    /** 完成治疗：治疗过程/操作记录必填，术后医嘱选填；以登录医生身份电子签名归档。 */
    public record TreatDoneCmd(String operator, String treatmentNote, String prescription) {}

    public record RetailCmd(String customerId, String storeCode, String consultant,
                            String project, List<M4FlowController.OrderItemCmd> items, String operator) {}

    /**
     * 售卡下单入参（B16）：customerId/storeCode/consultant/productCode（CD-/CS- 模板编码）。
     * 售价/卡名/次数/有效期一律以后端 store 域在售模板为准，不接收前端价格（防改价）。
     */
    public record CardSaleCmd(String customerId, String storeCode, String consultant,
                              String productCode, String operator) {}

    public record PlanItemView(String itemCode, String itemName, String spec, Integer qty,
                               Long unitPrice, Long amount, String riskTags) {}
    public record RevisionView(Long revId, String kind, String actorId, String actorName,
                               String reason, OffsetDateTime at) {}
    public record PlanView(String planId, String customerId, String customerName,
                           String storeCode, String storeName,
                           String consultantId, String consultantName,
                           String doctorId, String doctorName, String status,
                           String arrivalId, OffsetDateTime startedAt,
                           String conclusion, Long planAmount, Long planCost,
                           Object contraindications,
                           Boolean consentConsultant, Boolean consentCustomer,
                           String consentSignatureDataUrl,
                           String consentSignerName, String consentDocVersion,
                           OffsetDateTime consentAt, String skinReportId,
                           OffsetDateTime submittedAt, OffsetDateTime reviewedAt,
                           String reviewedByName, String rejectReason,
                           String emrId, OffsetDateTime emrSignedAt,
                           String orderNo, String orderStatus, OffsetDateTime paidAt,
                           Object preOp, OffsetDateTime treatingAt, OffsetDateTime treatedAt,
                           String treatNote, String treatPrescription, String treatEmrId,
                           OffsetDateTime createdAt,
                           List<PlanItemView> items, List<RevisionView> revisions) {}

    // ==================== 咨询师：保存草稿 / 接诊 / 提交方案审核 ====================

    /**
     * 保存咨询草稿：planId 为空→新建 PENDING 草稿（出 CP 单号）；planId 非空→更新既有草稿。
     * 草稿不做提交强校验（允许空结论/空项目/未签名），仅校验客户/门店存在性，便于咨询师边面诊边存。
     * 可保存状态：PENDING（待咨询）/ ACTIVE（咨询中）/ REJECTED（驳回改单中）；其余状态禁止覆盖。
     */
    @Transactional
    public PlanView saveDraft(SaveDraftCmd cmd) {
        if (blank(cmd.customerId())) throw bad("客户不能为空");
        if (!names.customerNames(List.of(cmd.customerId())).containsKey(cmd.customerId())) {
            throw bad("客户不存在: " + cmd.customerId());
        }
        if (blank(cmd.storeCode()) || !names.storeNames(List.of(cmd.storeCode())).containsKey(cmd.storeCode())) {
            throw bad("门店不存在或未指定: " + cmd.storeCode());
        }
        if (!blank(cmd.doctorId()) && !names.staffNames(List.of(cmd.doctorId())).containsKey(cmd.doctorId())) {
            throw bad("所选医生不存在: " + cmd.doctorId());
        }

        boolean isNew = blank(cmd.planId());
        ConsultPlan p;
        if (isNew) {
            p = new ConsultPlan();
            p.setPlanId(nextPlanNo());
            p.setCustomerId(cmd.customerId());
            p.setStatus("PENDING");
        } else {
            p = requirePlan(cmd.planId());
            String st = p.getStatus();
            if (!"PENDING".equals(st) && !"ACTIVE".equals(st) && !"REJECTED".equals(st)) {
                throw bad("当前方案单状态（" + st + "）不允许保存草稿");
            }
        }
        applyEditableFields(p, cmd.customerId(), cmd.storeCode(), cmd.consultantId(), cmd.doctorId(),
                cmd.conclusion(), cmd.contraindications(), cmd.consentConsultant(), cmd.consentCustomer(),
                cmd.consentSignatureDataUrl(), cmd.consentSignerName(), cmd.consentDocVersion(),
                cmd.skinReportId(), cmd.arrivalId());
        long total = rebuildItems(p.getPlanId(), cmd.items(), false);
        p.setPlanAmount(total);
        p.setPlanCost(Math.round(total * 0.35));
        planRepo.save(p);

        String reason = isNew ? "咨询草稿已创建" : "咨询草稿已保存";
        addRevision(p.getPlanId(), "SAVE_DRAFT", DataScope.currentActor(), reason, null);
        audit.record("PLAN", p.getPlanId(), actor(cmd.operator()), "SAVE_DRAFT",
                "{\"customer\":\"" + p.getCustomerId() + "\",\"items\":"
                        + (cmd.items() == null ? 0 : cmd.items().size()) + "}");
        return get(p.getPlanId());
    }

    /**
     * 接诊 / 开始咨询：PENDING → ACTIVE，首诊时间 startedAt 首次落库后不被覆盖。
     * 幂等：已 ACTIVE 直接返回当前视图（重复点击/网络重试不报错、不重复留痕）；其余状态拒绝。
     */
    @Transactional
    public PlanView start(String planId, StartCmd cmd) {
        String operator = cmd == null ? null : cmd.operator();
        String arrivalId = cmd == null ? null : cmd.arrivalId();
        ConsultPlan p = requirePlan(planId);
        if ("ACTIVE".equals(p.getStatus())) {
            return get(planId);
        }
        if (!"PENDING".equals(p.getStatus())) {
            throw bad("仅「待咨询」的草稿可开始咨询，当前: " + p.getStatus());
        }
        p.setStatus("ACTIVE");
        if (p.getStartedAt() == null) p.setStartedAt(OffsetDateTime.now());
        if (!blank(arrivalId) && blank(p.getArrivalId())) p.setArrivalId(arrivalId);
        planRepo.save(p);
        addRevision(planId, "START_CONSULT", DataScope.currentActor(), "已接诊，开始咨询面诊", null);
        audit.record("PLAN", planId, actor(operator), "START_CONSULT", "{}");
        return get(planId);
    }

    /**
     * 提交方案审核：planId 为空=直接提交（历史行为，新建后进 PENDING_REVIEW）；
     * planId 非空=草稿/驳回单续提（PENDING/ACTIVE 首提记 SUBMIT，REJECTED 改单重提记 RESUBMIT，驳回原因保留留痕）。
     */
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

        boolean fromDraft = !blank(cmd.planId());
        boolean resubmit = false;
        ConsultPlan p;
        if (fromDraft) {
            p = requirePlan(cmd.planId());
            String st = p.getStatus();
            if (!"PENDING".equals(st) && !"ACTIVE".equals(st) && !"REJECTED".equals(st)) {
                throw bad("当前方案单状态（" + st + "）不允许提交审核");
            }
            resubmit = "REJECTED".equals(st);
        } else {
            p = new ConsultPlan();
            p.setPlanId(nextPlanNo());
            p.setCustomerId(cmd.customerId());
        }
        applyEditableFields(p, cmd.customerId(), cmd.storeCode(), cmd.consultantId(), cmd.doctorId(),
                cmd.conclusion(), c, Boolean.TRUE, Boolean.TRUE,
                cmd.consentSignatureDataUrl(), cmd.consentSignerName(), cmd.consentDocVersion(),
                cmd.skinReportId(), cmd.arrivalId());
        long total = rebuildItems(p.getPlanId(), cmd.items(), true);
        p.setPlanAmount(total);
        p.setPlanCost(Math.round(total * 0.35));
        p.setConsentAt(p.getConsentAt() == null ? OffsetDateTime.now() : p.getConsentAt());
        p.setStatus("PENDING_REVIEW");
        p.setSubmittedAt(OffsetDateTime.now());
        planRepo.save(p);

        String kind = resubmit ? "RESUBMIT" : "SUBMIT";
        String reason = resubmit ? "驳回后改单重新提交医生审核" : "方案已与客户沟通确认，提交医生审核";
        addRevision(p.getPlanId(), kind, DataScope.currentActor(), reason, null);
        audit.record("PLAN", p.getPlanId(), actor(cmd.operator()), kind,
                "{\"customer\":\"" + p.getCustomerId() + "\",\"amount\":" + total
                        + ",\"items\":" + (cmd.items() == null ? 0 : cmd.items().size())
                        + ",\"doctor\":\"" + p.getDoctorId() + "\"}");
        return get(p.getPlanId());
    }

    /** 草稿/提交共用：把可编辑的方案单字段覆盖落实体（不含状态、金额、子项、时间戳）。 */
    private void applyEditableFields(ConsultPlan p, String customerId, String storeCode, String consultantId,
                                     String doctorId, String conclusion, ContraCmd c,
                                     Boolean consentConsultant, Boolean consentCustomer,
                                     String signatureDataUrl, String signerName, String docVersion,
                                     String skinReportId, String arrivalId) {
        p.setCustomerId(customerId);
        p.setStoreCode(storeCode);
        p.setConsultantId(consultantId);
        p.setDoctorId(doctorId);
        p.setConclusion(blank(conclusion) ? null : conclusion);
        p.setContraindicationsJson(toJson(c));
        p.setConsentConsultant(Boolean.TRUE.equals(consentConsultant));
        p.setConsentCustomer(Boolean.TRUE.equals(consentCustomer));
        p.setConsentSignatureDataUrl(blank(signatureDataUrl) ? null : signatureDataUrl);
        p.setConsentSignerName(blank(signerName) ? null : signerName);
        if (!blank(docVersion)) p.setConsentDocVersion(docVersion);
        else if (blank(p.getConsentDocVersion())) p.setConsentDocVersion("MEIYUN-ICF-v2026.1");
        p.setSkinReportId(blank(skinReportId) ? null : skinReportId);
        if (!blank(arrivalId)) p.setArrivalId(arrivalId);
    }

    /**
     * 重建方案子项（删旧重插），返回合计金额（分）。
     * strict=true 时逐行校验项目名（提交场景）；false 容忍空名行（草稿场景）。
     */
    private long rebuildItems(String planId, List<PlanItemCmd> items, boolean strict) {
        itemRepo.deleteByPlanId(planId);
        if (items == null || items.isEmpty()) return 0L;
        long total = 0L;
        int line = 1;
        List<PlanItem> entities = new ArrayList<>();
        for (PlanItemCmd it : items) {
            if (strict && blank(it.itemName())) throw bad("第 " + line + " 行项目名称不能为空");
            if (blank(it.itemName())) { line++; continue; }
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
        return total;
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

    // ==================== 售卡支线：现场售卡直开「待收款」订单（B16） ====================

    /**
     * 售卡下单：选建档客户 + 选 store 域在售卡项模板（CD-/CS-），后端取模板定价/次数/有效期，
     * 直接生成「待收款」售卡订单（bizKind=CARD_SALE，免医生审核）。散客也须是建档客户（开卡须挂客户）。
     *
     * <p>价格/卡名/次数/有效期一律以 store 域在售模板响应为准（不信前端，防改价）；模板不存在/本店不可售
     * 404、已下架 409、store 不可用 502 均中文透传中止开单。模板快照（productCode/cardType/totalTimes/
     * validityDays）冗余落订单，收款收齐同事务开卡只依赖 customer，不再回查 store——售卡那一刻的
     * 模板条款即合约，后续模板改价/下架不影响已售卡。售卡单禁止储值余额支付（PaymentService 拦截）。
     */
    @Transactional
    public M4FlowController.OrderView createCardOrder(CardSaleCmd cmd) {
        if (blank(cmd.customerId()) || !names.customerNames(List.of(cmd.customerId())).containsKey(cmd.customerId())) {
            throw bad("客户不存在或未选择: " + cmd.customerId()
                    + "（售卡开卡须挂建档客户；未建档请先建档再售卡）");
        }
        if (blank(cmd.storeCode()) || !names.storeNames(List.of(cmd.storeCode())).containsKey(cmd.storeCode())) {
            throw bad("门店不存在或未指定: " + cmd.storeCode());
        }
        if (blank(cmd.productCode())) {
            throw bad("未选择卡项模板，请先选择在售卡项");
        }
        Map<String, Object> tpl = catalogClient.getForSale(cmd.productCode(), cmd.storeCode());

        String cardItem = str(tpl.get("name"));
        String productCode = str(tpl.get("productCode"));
        String productType = str(tpl.get("productType"));
        long priceFen = tpl.get("priceFen") instanceof Number n ? n.longValue() : 0L;
        int sessions = tpl.get("sessions") instanceof Number s ? s.intValue() : 0;
        int validityDays = tpl.get("validityDays") instanceof Number v ? v.intValue() : 0;
        if (priceFen <= 0) {
            throw bad("卡项模板「" + cardItem + "」售价非法（须为正），无法售卡");
        }
        int totalTimes = sessions > 0 ? sessions : 1;

        TxnOrder order = new TxnOrder();
        order.setOrderNo(orderNoGen.nextOrderNo());
        order.setCustomerId(cmd.customerId());
        order.setStoreCode(cmd.storeCode());
        order.setProject(blank(cardItem) ? "售卡" : cardItem);
        order.setAmount(priceFen);
        order.setConsultant(cmd.consultant());
        order.setContraCheck("GREEN");
        order.setStatus("待收款");
        order.setBizKind("CARD_SALE");
        order.setProductCode(productCode);
        order.setCardType(blank(productType) ? null : productType);
        order.setCardTotalTimes(totalTimes);
        order.setCardValidityDays(Math.max(validityDays, 0));
        orderRepo.save(order);

        OrderItem oi = new OrderItem();
        oi.setOrderNo(order.getOrderNo());
        oi.setLineNo(1);
        oi.setItemName(blank(cardItem) ? "售卡" : cardItem);
        oi.setQty(1);
        oi.setUnitPrice(priceFen);
        oi.setAmount(priceFen);
        orderItemRepo.save(oi);

        audit.record("ORDER", order.getOrderNo(), actor(cmd.operator()), "CREATE",
                "{\"source\":\"CARD_SALE\",\"productCode\":\"" + esc(productCode)
                        + "\",\"cardType\":\"" + esc(productType) + "\",\"project\":\"" + esc(cardItem)
                        + "\",\"amount\":" + priceFen + ",\"totalTimes\":" + totalTimes
                        + ",\"validityDays\":" + Math.max(validityDays, 0) + "}");
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

    // ==================== 医生：治疗执行（PAID → TREATING → DONE） ====================

    /**
     * 开始治疗：PAID → TREATING。合规双前提（首程病历已签 + 缴费单已支付）由状态机本身保证。
     * 强校验：术前四项（知情同意归档 / 禁忌过敏复核 / 药品耗材批号 / 部位项目参数）全部确认；
     * 知情同意须有客户手写电子签名归档。幂等：TREATING 重复提交直接返回当前单（不重复落 treatingAt/留痕）。
     */
    @Transactional
    public PlanView treatStart(String planId, TreatStartCmd cmd) {
        ConsultPlan p = requirePlan(planId);
        final String actor = DataScope.currentActor();
        if ("TREATING".equals(p.getStatus()) || "DONE".equals(p.getStatus())) {
            return get(planId);
        }
        if (!"PAID".equals(p.getStatus())) {
            throw bad("仅「已支付·待治疗」的方案单可开始治疗，当前: " + p.getStatus()
                    + "（须先签首程病历并完成缴费单收款）");
        }
        if (cmd == null
                || !Boolean.TRUE.equals(cmd.consentChecked())
                || !Boolean.TRUE.equals(cmd.contraChecked())
                || !Boolean.TRUE.equals(cmd.drugChecked())
                || !Boolean.TRUE.equals(cmd.siteChecked())) {
            throw bad("术前核对四项必须全部确认：知情同意归档、禁忌/过敏复核、药品/耗材批号、治疗部位与参数");
        }
        if (blank(p.getConsentSignatureDataUrl())) {
            throw bad("未查到客户《知情同意书》手写电子签名，请回到咨询环节补签后再开始治疗");
        }

        Map<String, Object> preOp = new LinkedHashMap<>();
        preOp.put("consentChecked", true);
        preOp.put("contraChecked", true);
        preOp.put("drugChecked", true);
        preOp.put("siteChecked", true);
        preOp.put("room", cmd.room() == null ? "" : cmd.room().trim());
        preOp.put("note", cmd.note() == null ? "" : cmd.note().trim());
        OffsetDateTime now = OffsetDateTime.now();
        p.setPreOpJson(toJson(preOp));
        p.setTreatingAt(now);
        p.setStatus("TREATING");
        planRepo.save(p);

        String room = cmd.room() == null ? "" : cmd.room().trim();
        addRevision(planId, "TREAT_START", actor,
                "术前四项核对通过" + (blank(room) ? "" : "，" + room), null);
        audit.record("PLAN", planId, actor, "TREAT_START",
                "{\"room\":\"" + esc(room) + "\",\"preOp\":4}");
        return get(planId);
    }

    /**
     * 完成治疗：TREATING → DONE。治疗过程/操作记录必填，术后医嘱随治疗记录一并电子签名归档，
     * 生成治疗记录病历号（EM 号）。术后 SOP 多节点随访暂无后端域，不做自动排程（见交付文档边界）。
     * 幂等：DONE 重复提交直接返回当前单。
     */
    @Transactional
    public PlanView treatDone(String planId, TreatDoneCmd cmd) {
        ConsultPlan p = requirePlan(planId);
        final String actor = DataScope.currentActor();
        if ("DONE".equals(p.getStatus())) {
            return get(planId);
        }
        if (!"TREATING".equals(p.getStatus())) {
            throw bad("仅「治疗中」的方案单可完成治疗，当前: " + p.getStatus());
        }
        if (cmd == null || blank(cmd.treatmentNote())) {
            throw bad("请填写治疗过程 / 操作记录后再完成治疗（项目、参数、术中反应、生命体征等）");
        }
        String note = cmd.treatmentNote().trim();
        String prescription = cmd.prescription() == null ? null : cmd.prescription().trim();
        String emrNo = nextEmrNo();
        OffsetDateTime now = OffsetDateTime.now();
        p.setTreatNote(note);
        p.setTreatPrescription(prescription);
        p.setTreatEmrId(emrNo);
        p.setTreatedAt(now);
        p.setStatus("DONE");
        planRepo.save(p);

        addRevision(planId, "TREAT_DONE", actor,
                "治疗记录 " + emrNo + " 已电子签名归档", null);
        audit.record("PLAN", planId, actor, "TREAT_DONE",
                "{\"treatEmr\":\"" + emrNo + "\",\"noteLen\":" + note.length() + "}");
        return get(planId);
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
                p.getStatus(), p.getArrivalId(), p.getStartedAt(),
                p.getConclusion(), p.getPlanAmount(), p.getPlanCost(),
                parseJson(p.getContraindicationsJson()),
                p.getConsentConsultant(), p.getConsentCustomer(),
                p.getConsentSignatureDataUrl(),
                p.getConsentSignerName(), p.getConsentDocVersion(),
                p.getConsentAt(), p.getSkinReportId(),
                p.getSubmittedAt(), p.getReviewedAt(),
                blank(p.getReviewedBy()) ? p.getReviewedByName() : staff.get(p.getReviewedBy()),
                p.getRejectReason(), p.getEmrId(), p.getEmrSignedAt(),
                p.getOrderNo(), orderStatus, p.getPaidAt(),
                parseJson(p.getPreOpJson()), p.getTreatingAt(), p.getTreatedAt(),
                p.getTreatNote(), p.getTreatPrescription(), p.getTreatEmrId(),
                p.getCreatedAt(),
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
                o.getProject(), o.getAmount(), o.getStatus(), o.getBizKind(),
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
    /** 跨服务模板 Map 取值转字符串（null/非字符串安全回落空串）。 */
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    /** 审计/修订留痕操作人：一律取 JWT 登录人工号（请求体 operator 字段不可信，忽略）；无上下文回落 system。 */
    private static String actor(String a) { return DataScope.currentActor(); }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
