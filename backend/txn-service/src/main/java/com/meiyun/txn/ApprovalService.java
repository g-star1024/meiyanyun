package com.meiyun.txn;

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
import java.util.List;

/**
 * 统一审批中心服务（T3-01）：审批待办提交 / 同意 / 驳回 / 转交 / 加签，全链路留痕。
 * 退款（RF）/ 退卡（CC）创建时由 TxnService 同事务调 {@link #submitForTxn} 生成待办：
 * L1 直达 FINANCE（财务复核），L2/L3 起始于 REVIEW（店长/运营一审）。
 * 审批动作同事务回写 TxnService 状态机：
 * REVIEW 同意 → txn.approve（PENDING_REVIEW→PENDING_FINANCE）；
 * FINANCE 同意 → txn.confirmRefund（PENDING_FINANCE→REFUNDED，终审）；
 * 任一阶段驳回 → txn.reject（→REJECTED，原因透传）。
 * 其余六类业务（TRANSFER/LEAVE/PROCUREMENT/PRICE_CHANGE/LOSS_REPORT/REQUISITION）
 * 后端暂无落地业务单，支持待办存储与展示，回写在对应域上线后接入（M5+）。
 */
@Service
public class ApprovalService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ApprovalTodoRepository repo;
    private final TxnService txnService;
    private final AuditRecorder audit;

    public ApprovalService(ApprovalTodoRepository repo, @Lazy TxnService txnService, AuditRecorder audit) {
        this.repo = repo;
        this.txnService = txnService;
        this.audit = audit;
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

    // ---------------- 审批动作 ----------------

    /**
     * 同意：REVIEW 一审通过 → 推进 FINANCE 并回写业务单；FINANCE 终审通过 → 办结并回写业务单完成。
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
        boolean finalStage = "FINANCE".equals(t.getStage());
        appendHistory(t, actor, "APPROVE", comment, now);

        TxnService.ApprovalCmd writeback = new TxnService.ApprovalCmd(actor, comment);
        if ("REFUND".equals(t.getBizType()) || "CARD_CANCEL".equals(t.getBizType())) {
            if (finalStage) {
                txnService.confirmRefund(t.getBizNo(), writeback);
            } else {
                txnService.approve(t.getBizNo(), writeback);
            }
        }

        if (finalStage) {
            t.setStatus("APPROVED");
        } else {
            t.setStage("FINANCE");
            t.setAssignee(null);
        }
        repo.save(t);
        audit.record("APPROVAL", todoNo, actor, "APPROVE",
                String.format("{\"bizType\":\"%s\",\"bizNo\":\"%s\",\"stage\":\"%s\",\"final\":%b,\"comment\":%s}",
                        t.getBizType(), t.getBizNo(), finalStage ? "FINANCE" : "REVIEW", finalStage, jsonStr(comment)));
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
     * 审批/驳回前置闸门：① 阶段角色——REVIEW 须店长（或超管），FINANCE 须财务（或超管）；
     * ② 指派人——已明确指派时仅指派人/会签人/超管可操作，指派人为空则按角色路由放行。
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
}
