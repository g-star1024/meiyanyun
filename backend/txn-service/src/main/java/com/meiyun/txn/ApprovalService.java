package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一审批中心服务（T3-01）：审批待办提交 / 同意 / 驳回 / 转交 / 加签，全链路留痕。
 * 退款（RF）/ 退卡（CC）创建时由 TxnService 同事务调 {@link #submitForTxn} 生成待办：
 * L1 直达 FINANCE（财务复核），L2/L3 起始于 REVIEW（店长/运营一审）。
 * 审批动作同事务回写 TxnService 状态机（B19 三阶段）：
 * L3（≥¥20,000）REVIEW 店长初审同意 → 仅推进 REGION（不回写业务单）；
 * REGION 区域经理复审同意 → txn.approve（PENDING_REVIEW→PENDING_FINANCE）并推进 FINANCE；
 * L2 REVIEW 同意 → txn.approve 并推进 FINANCE；
 * FINANCE 同意 → txn.confirmRefund（PENDING_FINANCE→REFUNDED，终审）；
 * 任一阶段驳回 → txn.reject（→REJECTED，原因透传）。
 * 阶段角色闸门：REVIEW 须 STORE_MGR、REGION 须 REGION_MGR、FINANCE 须 FINANCE（超管放行）。
 * 耗材领用（REQUISITION）/ 报损（LOSS_REPORT）为 B5 双签闭环业务（口径定案并入审批中心）：
 * 提交时明细 SKU 行存 payload（TEXT JSON），终审（FINANCE）通过同事务回调库存域扣库
 * （{@link StoreConsumableClient}，远程失败整事务回滚）并由 {@link FinanceEventPublisher}
 * 入 outbox 落 TK-MATERIAL/TK-LOSS 成本；REVIEW 一审仅推进财务，无下游动作。
 * 其余四类（TRANSFER/LEAVE/PROCUREMENT/PRICE_CHANGE）后端暂无落地业务单，回写在对应域上线后接入（M5+）。
 */
@Service
public class ApprovalService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalTodoRepository repo;
    private final TxnService txnService;
    private final AuditRecorder audit;
    private final StoreConsumableClient storeConsumableClient;
    private final FinanceEventPublisher financeEventPublisher;
    private final ApptRefNameResolver nameResolver;

    public ApprovalService(ApprovalTodoRepository repo, @Lazy TxnService txnService, AuditRecorder audit,
                           StoreConsumableClient storeConsumableClient,
                           FinanceEventPublisher financeEventPublisher,
                           ApptRefNameResolver nameResolver) {
        this.repo = repo;
        this.txnService = txnService;
        this.audit = audit;
        this.storeConsumableClient = storeConsumableClient;
        this.financeEventPublisher = financeEventPublisher;
        this.nameResolver = nameResolver;
    }

    // ---------------- 提交待办（退款/退卡创建同事务联动） ----------------

    /**
     * 退款/退卡创建审批待办。
     *
     * @param bizType   REFUND / CARD_CANCEL
     * @param bizNo     业务单号（RF.../CC...），审批回写直接用作 txnNo
     * @param amount    审批金额（分）
     * @param tier      L1/L2/L3
     * @param storeCode 业务单所属门店码（数据域过滤/详情断言依据）
     */
    @Transactional
    public ApprovalTodo submitForTxn(String bizType, String bizNo, String title, String summary,
                                     Long amount, String tier, String storeCode) {
        ApprovalTodo t = new ApprovalTodo();
        t.setTodoNo(nextNo());
        t.setBizType(bizType);
        t.setBizNo(bizNo);
        t.setTitle(title);
        t.setSummary(summary);
        t.setAmount(amount);
        t.setStoreCode(storeCode);
        t.setApplicant(currentActor());
        t.setApplicantRole("OPERATOR");
        t.setSignTier(tier);
        t.setStatus("PENDING");
        // B19：L3 大额（≥¥20,000）退款/退卡三阶段——REVIEW 店长初审 → REGION 区域经理复审 → FINANCE 财务终审；
        // L1 直达财务；L2 两阶段（店长 → 财务）。
        t.setStage("L1".equals(tier) ? "FINANCE" : "REVIEW");
        t.setPriority("L3".equals(tier) ? "HIGH" : "MEDIUM");
        OffsetDateTime now = OffsetDateTime.now();
        t.setSubmittedAt(now);
        t.setCoSigners("");
        t.setHistory(historyJson(new HistoryEntry(currentActor(), "SUBMIT", "提交审批", now)));
        repo.save(t);
        audit.record("APPROVAL", t.getTodoNo(), currentActor(), "SUBMIT",
                String.format("{\"bizType\":\"%s\",\"bizNo\":\"%s\",\"tier\":\"%s\",\"stage\":\"%s\",\"amount\":%s}",
                        bizType, bizNo, tier, t.getStage(), amount == null ? "null" : amount.toString()));
        return t;
    }

    /**
     * 耗材领用提交（B5）：领用单金额以库存移动平均成本出库时定格，提交端无金额，
     * 固定双签（店长一审 REVIEW → 财务终审 FINANCE），终审通过回调 store 扣 USE 库存并落 TK-MATERIAL 成本。
     */
    @Transactional
    public ApprovalTodo submitRequisition(RequisitionCmd cmd) {
        List<ConsumableLine> lines = validLines(cmd.lines(), "领用");
        guardWriteStore(cmd.storeCode());
        String purpose = nz(cmd.purpose(), "耗材领用");
        return submitConsumable("REQUISITION", cmd.storeCode(), purpose, "耗材领用", lines, null);
    }

    /**
     * 耗材报损提交（B5）：损失额（分，前端元换算）决定签署层级——&lt;¥5000 财务单签直达，
     * ≥¥5000 店长一审 + 财务终审；终审通过回调 store 扣 SCRAP 库存并落 TK-LOSS 成本。
     */
    @Transactional
    public ApprovalTodo submitLossReport(LossReportCmd cmd) {
        if (cmd.amount() == null || cmd.amount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报损必须填写损失金额（分，且大于 0）");
        }
        List<ConsumableLine> lines = validLines(cmd.lines(), "报损");
        guardWriteStore(cmd.storeCode());
        String reason = nz(cmd.reason(), "耗材报损");
        return submitConsumable("LOSS_REPORT", cmd.storeCode(), reason, "耗材报损", lines, cmd.amount());
    }

    /** 领用/报损统一建单：tier 报损按金额（tierFor）、领用固定 L2；payload 存 SKU 行 JSON（终审扣库依据）。 */
    private ApprovalTodo submitConsumable(String bizType, String storeCode, String reason,
                                          String label, List<ConsumableLine> lines, Long amount) {
        String tier = amount == null ? "L2" : TxnService.tierFor(amount);
        String todoNo = nextNo();
        ApprovalTodo t = new ApprovalTodo();
        t.setTodoNo(todoNo);
        t.setBizType(bizType);
        t.setBizNo(todoNo);
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) detail.append("、");
            detail.append(nz(lines.get(i).name(), lines.get(i).skuCode())).append("×").append(lines.get(i).qty());
        }
        t.setTitle(label + " · " + reason);
        t.setSummary(truncate(detail + (amount != null ? "（损失 ¥" + (amount / 100.0) + "）" : ""), 255));
        t.setAmount(amount);
        t.setStoreCode(storeCode);
        t.setStoreName(storeName(storeCode));
        t.setApplicant(currentActor());
        t.setApplicantRole("OPERATOR");
        t.setSignTier(tier);
        t.setStatus("PENDING");
        t.setStage("L1".equals(tier) ? "FINANCE" : "REVIEW");
        t.setPriority(amount != null && amount >= 2_000_000L ? "HIGH" : "MEDIUM");
        OffsetDateTime now = OffsetDateTime.now();
        t.setSubmittedAt(now);
        t.setCoSigners("");
        t.setHistory(historyJson(new HistoryEntry(currentActor(), "SUBMIT", "提交审批", now)));
        t.setPayload(payloadJson(lines));
        repo.save(t);
        audit.record("APPROVAL", todoNo, currentActor(), "SUBMIT",
                String.format("{\"bizType\":\"%s\",\"bizNo\":\"%s\",\"tier\":\"%s\",\"stage\":\"%s\",\"lines\":%d,\"amount\":%s}",
                        bizType, todoNo, tier, t.getStage(), lines.size(), amount == null ? "null" : amount.toString()));
        return t;
    }

    /** 明细行校验：非空、逐行 skuCode 必填、qty 为正整数；重复 SKU 报 400（扣库逐行独立，防重复行）。 */
    private static List<ConsumableLine> validLines(List<ConsumableLine> lines, String label) {
        if (lines == null || lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "明细不能为空（至少一行耗材）");
        }
        List<ConsumableLine> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ConsumableLine l : lines) {
            if (l == null || l.skuCode() == null || l.skuCode().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "明细每行必须选择耗材（skuCode 必填）");
            }
            if (l.qty() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "耗材「" + nz(l.name(), l.skuCode()) + "」数量必须为正整数");
            }
            if (seen.contains(l.skuCode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "耗材「" + nz(l.name(), l.skuCode()) + "」重复出现，请合并为同一行");
            }
            seen.add(l.skuCode());
            out.add(l);
        }
        return out;
    }

    /** 写闸门：登录人须对目标门店有数据域权限（门店角色仅本店；越权统一 404 不泄露门店存在性）。 */
    private static void guardWriteStore(String storeCode) {
        if (storeCode == null || storeCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须指定所属门店（storeCode 必填）");
        }
        if (!DataScope.canReadStore(storeCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权操作该门店");
        }
    }

    /** 门店名回填（name-map 失败降级空名，不阻断提交）。 */
    private String storeName(String storeCode) {
        try {
            return nameResolver.storeNames(List.of(storeCode)).get(storeCode);
        } catch (Exception e) {
            return null;
        }
    }

    /** 明细行序列化 payload（[{skuCode,name,qty,remark}]）；失败属编程错误快速失败回滚。 */
    private static String payloadJson(List<ConsumableLine> lines) {
        try {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (ConsumableLine l : lines) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("skuCode", l.skuCode());
                m.put("name", l.name());
                m.put("qty", l.qty());
                m.put("remark", l.remark());
                arr.add(m);
            }
            return MAPPER.writeValueAsString(arr);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "领用明细内容序列化失败");
        }
    }

    /**
     * 终审扣库（仅 FINANCE 终审、仅领用/报损）：解析 payload 明细 → 系统身份回调 store 扣库
     * （bizRef=待办号幂等；远程失败抛异常整事务回滚，待办不置 APPROVED）→ 成本事件入 outbox。
     * 幂等重放由 store 侧（同 bizRef+SKU 不双扣、回返原行定格金额）与 finance idem_key 双保险。
     */
    private void deductOnFinalApprove(ApprovalTodo t, String actor) {
        boolean scrap = "LOSS_REPORT".equals(t.getBizType());
        List<StoreConsumableClient.DeductLine> lines;
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(
                    t.getPayload() == null ? "[]" : t.getPayload(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
            lines = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                Object sku = m.get("skuCode");
                Object qty = m.get("qty");
                if (sku == null || String.valueOf(sku).isBlank()) continue;
                int q = qty instanceof Number n ? n.intValue() : 0;
                lines.add(new StoreConsumableClient.DeductLine(String.valueOf(sku), q,
                        m.get("remark") == null ? null : String.valueOf(m.get("remark"))));
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "审批明细内容缺失或已损坏，无法出库，请联系管理员核对该待办明细");
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "审批明细内容缺失或已损坏，无法出库，请联系管理员核对该待办明细");
        }
        StoreConsumableClient.DeductResult result = storeConsumableClient.deduct(
                t.getTodoNo(), t.getStoreCode(), scrap ? "SCRAP" : "USE", actor, lines);
        financeEventPublisher.emitConsumableCost(
                t.getTodoNo(), t.getStoreCode(), scrap ? "SCRAP" : "USE", result.totalAmountFen());
    }

    private static String nz(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------- 审批动作 ----------------

    /**
     * 同意（B19 三阶段状态机）：
     * <ul>
     *   <li>退款/退卡 L3：REVIEW 店长初审 → REGION 区域经理复审（回写业务单 PENDING_REVIEW→PENDING_FINANCE）
     *       → FINANCE 财务终审（confirmRefund 办结）；L2 无 REGION，REVIEW 通过即推进 FINANCE；L1 直达 FINANCE。</li>
     *   <li>耗材领用/报损：两阶段不变，REVIEW → FINANCE，终审回调库存域扣库。</li>
     *   <li>L3 的 REVIEW 一审通过不回写业务单（业务单保持 PENDING_REVIEW，区域复审通过才进入待财务）；
     *       L2 的 REVIEW 一审通过即回写业务单 PENDING_REVIEW→PENDING_FINANCE 推进财务。</li>
     * </ul>
     */
    @Transactional
    public ApprovalTodo approve(String todoNo, ActionCmd cmd) {
        ApprovalTodo t = require(todoNo);
        if (!"PENDING".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "待办状态「" + t.getStatus() + "」不可审批（仅待处理待办可操作）");
        }
        guardStageAssignee(t);
        String actor = currentActor();
        String comment = cmd.comment() == null || cmd.comment().isBlank() ? "同意" : cmd.comment();
        OffsetDateTime now = OffsetDateTime.now();
        String stage = t.getStage();
        boolean finalStage = "FINANCE".equals(stage);
        appendHistory(t, actor, "APPROVE", comment, now);

        TxnService.ApprovalCmd writeback = new TxnService.ApprovalCmd(actor, comment);
        if ("REFUND".equals(t.getBizType()) || "CARD_CANCEL".equals(t.getBizType())) {
            if (finalStage) {
                txnService.confirmRefund(t.getBizNo(), writeback);
            } else if ("REGION".equals(stage)) {
                // L3 区域经理复审通过：业务单 PENDING_REVIEW → PENDING_FINANCE，待办推进财务终审；第三签留痕
                txnService.approve(t.getBizNo(), writeback);
                txnService.markThirdSign(t.getBizNo(), actor);
            } else {
                // REVIEW 一审通过：L2 无区域复审，回写业务单 PENDING_REVIEW → PENDING_FINANCE 并推进财务；
                // L3 的 REVIEW 通过不回写（业务单保持 PENDING_REVIEW，待区域复审通过才进入待财务）
                if (!"L3".equals(t.getSignTier())) {
                    txnService.approve(t.getBizNo(), writeback);
                }
            }
        } else if (("REQUISITION".equals(t.getBizType()) || "LOSS_REPORT".equals(t.getBizType()))
                && finalStage) {
            // 领用/报损：REVIEW 一审仅推进财务无下游；FINANCE 终审通过回调库存域扣库 + 成本入 outbox（失败抛异常回滚）
            deductOnFinalApprove(t, actor);
        }

        if (finalStage) {
            t.setStatus("APPROVED");
        } else if ("REVIEW".equals(stage) && "L3".equals(t.getSignTier())
                && ("REFUND".equals(t.getBizType()) || "CARD_CANCEL".equals(t.getBizType()))) {
            // B19：L3 退款/退卡插入区域经理复审阶段
            t.setStage("REGION");
            t.setAssignee(null);
        } else {
            t.setStage("FINANCE");
            t.setAssignee(null);
        }
        repo.save(t);
        audit.record("APPROVAL", todoNo, actor, "APPROVE",
                String.format("{\"bizType\":\"%s\",\"bizNo\":\"%s\",\"stage\":\"%s\",\"nextStage\":\"%s\",\"final\":%b,\"comment\":%s}",
                        t.getBizType(), t.getBizNo(), stage, t.getStage(), finalStage, jsonStr(comment)));
        return t;
    }

    /** 驳回：任一阶段可驳回（原因必填）；REFUND/CARD_CANCEL 回写业务单 REJECTED。 */
    @Transactional
    public ApprovalTodo reject(String todoNo, ActionCmd cmd) {
        if (cmd.comment() == null || cmd.comment().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回必须填写原因");
        }
        ApprovalTodo t = require(todoNo);
        if (!"PENDING".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "待办状态「" + t.getStatus() + "」不可驳回（仅待处理待办可操作）");
        }
        guardStageAssignee(t);
        String actor = currentActor();
        OffsetDateTime now = OffsetDateTime.now();
        appendHistory(t, actor, "REJECT", cmd.comment(), now);
        t.setStatus("REJECTED");

        if ("REFUND".equals(t.getBizType()) || "CARD_CANCEL".equals(t.getBizType())) {
            txnService.reject(t.getBizNo(), new TxnService.ApprovalCmd(actor, cmd.comment()));
        }
        repo.save(t);
        audit.record("APPROVAL", todoNo, actor, "REJECT",
                String.format("{\"bizType\":\"%s\",\"bizNo\":\"%s\",\"stage\":\"%s\",\"reason\":%s}",
                        t.getBizType(), t.getBizNo(), t.getStage(), jsonStr(cmd.comment())));
        return t;
    }

    /** 转交：仅改指派人并留痕，不推进状态机。 */
    @Transactional
    public ApprovalTodo transfer(String todoNo, TransferCmd cmd) {
        if (cmd.to() == null || cmd.to().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "转交必须指定目标审批人");
        }
        ApprovalTodo t = require(todoNo);
        if (!"PENDING".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "待办状态「" + t.getStatus() + "」不可转交（仅待处理待办可操作）");
        }
        String actor = currentActor();
        t.setAssignee(cmd.to().trim());
        String comment = "转交给 " + cmd.to().trim() + (cmd.comment() == null || cmd.comment().isBlank() ? "" : "：" + cmd.comment());
        appendHistory(t, actor, "TRANSFER", comment, OffsetDateTime.now());
        repo.save(t);
        audit.record("APPROVAL", todoNo, actor, "TRANSFER",
                String.format("{\"bizNo\":\"%s\",\"to\":%s,\"comment\":%s}",
                        t.getBizNo(), jsonStr(cmd.to().trim()), jsonStr(cmd.comment())));
        return t;
    }

    /** 加签：追加会签人（去重），不改变当前审批人与阶段。 */
    @Transactional
    public ApprovalTodo addSigner(String todoNo, AddSignerCmd cmd) {
        if (cmd.who() == null || cmd.who().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "加签必须指定会签人");
        }
        ApprovalTodo t = require(todoNo);
        if (!"PENDING".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "待办状态「" + t.getStatus() + "」不可加签（仅待处理待办可操作）");
        }
        String actor = currentActor();
        String who = cmd.who().trim();
        List<String> signers = coSignerList(t.getCoSigners());
        if (!signers.contains(who)) {
            signers.add(who);
            t.setCoSigners(String.join(",", signers));
        }
        appendHistory(t, actor, "ADD_SIGN", "加签 " + who, OffsetDateTime.now());
        repo.save(t);
        audit.record("APPROVAL", todoNo, actor, "ADD_SIGN",
                String.format("{\"bizNo\":\"%s\",\"who\":%s,\"coSigners\":%s}",
                        t.getBizNo(), jsonStr(who), jsonStr(t.getCoSigners())));
        return t;
    }

    // ---------------- 查询 ----------------

    /**
     * 列表：tab=todo（待处理）/ done（已办结）/ all（全部）；可叠加 bizType 过滤。
     * 数据域：storeSpec 按门店码注入；todo tab 额外做「我的待办」服务端过滤——
     * 指派人为空（按角色路由，当前人凭权限可处理）/ 指派给我 / 会签含我 / 我提交的。
     */
    public List<ApprovalTodo> list(String tab, String bizType) {
        boolean hasType = bizType != null && !bizType.isBlank() && !"ALL".equals(bizType);
        boolean done = "done".equalsIgnoreCase(tab);
        boolean todo = "todo".equalsIgnoreCase(tab);
        Specification<ApprovalTodo> spec = DataScope.storeSpec("storeCode");
        if (todo) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), "PENDING"));
        } else if (done) {
            spec = spec.and((r, q, cb) -> cb.notEqual(r.get("status"), "PENDING"));
        }
        if (hasType) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("bizType"), bizType));
        }
        if (todo) {
            var u = DataScope.current();
            String me = u == null ? null : u.staffId();
            spec = spec.and((r, q, cb) -> {
                var unassigned = cb.or(cb.isNull(r.get("assignee")), cb.equal(r.get("assignee"), ""));
                if (me == null || me.isBlank()) {
                    return unassigned;
                }
                return cb.or(unassigned,
                        cb.equal(r.get("assignee"), me),
                        cb.like(r.get("coSigners"), "%" + me + "%"),
                        cb.equal(r.get("applicant"), me));
            });
        }
        return repo.findAll(spec, Sort.by(Sort.Order.desc("submittedAt")));
    }

    public ApprovalTodo find(String todoNo) {
        return require(todoNo);
    }

    // ---------------- 内部辅助 ----------------

    private ApprovalTodo require(String todoNo) {
        ApprovalTodo t = repo.findById(todoNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(t.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return t;
    }

    /**
     * 审批/驳回前置闸门：① 阶段角色——REVIEW 须店长（或超管），REGION 须区域经理（或超管，B19 L3 复审），
     * FINANCE 须财务（或超管）；② 指派人——已明确指派时仅指派人/会签人/超管可操作，指派人为空则按角色路由放行。
     */
    private void guardStageAssignee(ApprovalTodo t) {
        LoginUser u = DataScope.current();
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效");
        }
        if (!u.isSuper()) {
            List<String> roles = u.roles() == null ? List.of() : u.roles();
            if ("FINANCE".equals(t.getStage())) {
                if (!roles.contains("FINANCE")) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前阶段需财务审批");
                }
            } else if ("REGION".equals(t.getStage())) {
                if (!roles.contains("REGION_MGR")) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前阶段需区域经理审批");
                }
            } else if ("REVIEW".equals(t.getStage())) {
                if (!roles.contains("STORE_MGR")) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前阶段需店长审批");
                }
            }
            String assignee = t.getAssignee();
            if (assignee != null && !assignee.isBlank() && !assignee.equals(u.staffId())
                    && !coSignerList(t.getCoSigners()).contains(u.staffId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该待办已指派他人处理");
            }
        }
    }

    /** 当前操作人：统一委托 {@link DataScope#currentActor()}（JWT 登录人工号，body actor 忽略；匿名回落 system）。 */
    private static String currentActor() {
        return DataScope.currentActor();
    }

    private static List<String> coSignerList(String raw) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) return list;
        for (String s : raw.split(",")) {
            if (!s.isBlank() && !list.contains(s.trim())) list.add(s.trim());
        }
        return list;
    }

    private void appendHistory(ApprovalTodo t, String actor, String action, String comment, OffsetDateTime at) {
        String entry = "{\"actor\":" + jsonStr(actor) + ",\"action\":\"" + action + "\",\"comment\":"
                + jsonStr(comment) + ",\"at\":" + jsonStr(at.toString()) + "}";
        String h = t.getHistory();
        if (h == null || h.isBlank() || "[]".equals(h.trim())) {
            t.setHistory("[" + entry + "]");
        } else {
            String trimmed = h.trim();
            t.setHistory(trimmed.substring(0, trimmed.length() - 1) + "," + entry + "]");
        }
    }

    private static String historyJson(HistoryEntry e) {
        return "[" + "{\"actor\":" + jsonStr(e.actor()) + ",\"action\":\"" + e.action()
                + "\",\"comment\":" + jsonStr(e.comment()) + ",\"at\":" + jsonStr(e.at().toString()) + "}]";
    }

    private record HistoryEntry(String actor, String action, String comment, OffsetDateTime at) {
    }

    /** 业务单号：AP + yyyyMMdd + '-' + 6 位当日序号（DB maxSeq+1，synchronized 防并发同号）。 */
    private synchronized String nextNo() {
        String day = OffsetDateTime.now().format(NO_DATE);
        long next = repo.maxSeqOfDay("AP" + day + "-%") + 1;
        return "AP" + day + "-" + String.format("%06d", next);
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------- 命令 DTO ----------------

    /** 同意/驳回落参（驳回 comment 必填，由 service 校验）。 */
    public record ActionCmd(String actor, String comment) {
    }

    /** 转交落参。 */
    public record TransferCmd(String actor, String to, String comment) {
    }

    /** 加签落参。 */
    public record AddSignerCmd(String actor, String who) {
    }

    /** 领用/报损明细行（前端选 SKU；name 仅展示留痕，扣库以 skuCode 为准）。 */
    public record ConsumableLine(String skuCode, String name, int qty, String remark) {
    }

    /** 耗材领用提交落参（操作人取 JWT 登录人，actor 忽略；无金额，成本出库时定格）。 */
    public record RequisitionCmd(String actor, String storeCode, String purpose, List<ConsumableLine> lines) {
    }

    /** 耗材报损提交落参（amount=损失金额分，前端元换算；决定签署层级）。 */
    public record LossReportCmd(String actor, String storeCode, String reason, Long amount,
                                List<ConsumableLine> lines) {
    }
}
