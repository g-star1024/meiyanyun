package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 撞单合并写期端点（P5-B84，DESIGN-P5-B84 §5 五端点契约）。
 *
 * <p>全部端点 @RequirePerm("customer:merge")（超管/区域/店长三角色，D3 维持现状矩阵）。
 * 写接口四件套全配：参数校验（pair 解析/状态守卫/必填事由）＋幂等（idem_key UNIQUE＋重复返回
 * 当前态不重复审计）＋全动作审计（PROPOSE/APPROVE/REJECT/EXECUTE/DISMISS，biz_type=CUSTOMER_MERGE）
 * ＋中文错误（GlobalExceptionHandler 映射 4xx）。
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerMergeController {

    private final MergeExecuteService mergeService;
    private final ObjectMapper om;

    @Autowired
    private AuditRecorder audit;

    public CustomerMergeController(MergeExecuteService mergeService, ObjectMapper om) {
        this.mergeService = mergeService;
        this.om = om;
    }

    /** 发起合并请求体：pairId 或 customerIdA/B 二选一；masterId 缺省=创建时间最早方；idemKey 幂等。 */
    public record ProposeCmd(String pairId, String customerIdA, String customerIdB,
                             String masterId, String reason, String idemKey) {
    }

    /** 驳回请求体：reason 驳回理由。 */
    public record RejectCmd(String reason) {
    }

    /** 标记非重复请求体：customerIdA/customerIdB 双侧编号＋reason 非重复理由＋可选 idemKey。 */
    public record DismissCmd(String customerIdA, String customerIdB, String reason, String idemKey) {
    }

    /**
     * 发起合并：D2 直接执行链（PROPOSED→APPROVED→MERGED 同事务），返回终态单据。
     * 幂等重放返回已有单据当前态，不重复执行、不重复审计。
     */
    @PostMapping("/merges")
    @RequirePerm("customer:merge")
    public CustomerMerge propose(@RequestBody ProposeCmd cmd) {
        if (cmd == null) {
            throw new CustomerService.BadReq("请求体不能为空");
        }
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw new CustomerService.BadReq("请填写合并事由");
        }
        MergeExecuteService.ProposeResult result = mergeService.propose(
                cmd.pairId(), cmd.customerIdA(), cmd.customerIdB(),
                cmd.masterId(), cmd.reason().trim(), cmd.idemKey());
        CustomerMerge m = result.merge();
        if (result.executed()) {
            audit.record("CUSTOMER_MERGE", m.getMergeId(), DataScope.currentActor(), "PROPOSE",
                    "{\"mergeId\":\"" + esc(m.getMergeId())
                            + "\",\"masterId\":\"" + esc(m.getMasterId())
                            + "\",\"mergedId\":\"" + esc(m.getMergedId())
                            + "\",\"groupType\":\"" + esc(m.getGroupType())
                            + "\",\"reason\":\"" + esc(m.getReason()) + "\"}");
            recordExecute(m, result.execute());
        }
        return m;
    }

    /** 审批通过并执行（状态机骨架端点：PROPOSED/REVIEWING 单据用；MERGED 幂等返回当前态不重复审计）。 */
    @PostMapping("/merges/{id}/approve")
    @RequirePerm("customer:merge")
    public CustomerMerge approve(@PathVariable("id") String id) {
        MergeExecuteService.ApproveResult result = mergeService.approve(id);
        CustomerMerge m = result.merge();
        if (result.executed()) {
            audit.record("CUSTOMER_MERGE", m.getMergeId(), DataScope.currentActor(), "APPROVE",
                    "{\"mergeId\":\"" + esc(m.getMergeId())
                            + "\",\"approvedBy\":\"" + esc(m.getApprovedBy()) + "\"}");
            recordExecute(m, result.execute());
        }
        return m;
    }

    /** 驳回：PROPOSED/REVIEWING/APPROVED → REJECTED，附理由。 */
    @PostMapping("/merges/{id}/reject")
    @RequirePerm("customer:merge")
    public CustomerMerge reject(@PathVariable("id") String id, @RequestBody(required = false) RejectCmd cmd) {
        String reason = cmd == null ? null : cmd.reason();
        CustomerMerge m = mergeService.reject(id, reason);
        audit.record("CUSTOMER_MERGE", m.getMergeId(), DataScope.currentActor(), "REJECT",
                "{\"mergeId\":\"" + esc(m.getMergeId())
                        + "\",\"reason\":\"" + esc(m.getReason()) + "\"}");
        return m;
    }

    /** 标记非重复：status=NOT_DUPLICATE 落痕，候选发现双向排除该 pair。幂等重放返回已有单据不重复审计。 */
    @PostMapping("/merge-dismiss")
    @RequirePerm("customer:merge")
    public CustomerMerge dismiss(@RequestBody DismissCmd cmd) {
        if (cmd == null) {
            throw new CustomerService.BadReq("请求体不能为空");
        }
        if (cmd.reason() == null || cmd.reason().isBlank()) {
            throw new CustomerService.BadReq("请填写非重复理由");
        }
        MergeExecuteService.DismissResult result = mergeService.dismiss(
                cmd.customerIdA(), cmd.customerIdB(), cmd.reason().trim(), cmd.idemKey());
        CustomerMerge m = result.merge();
        if (result.created()) {
            audit.record("CUSTOMER_MERGE", m.getMergeId(), DataScope.currentActor(), "DISMISS",
                    "{\"mergeId\":\"" + esc(m.getMergeId())
                            + "\",\"customerIdA\":\"" + esc(m.getMasterId())
                            + "\",\"customerIdB\":\"" + esc(m.getMergedId())
                            + "\",\"reason\":\"" + esc(m.getReason()) + "\"}");
        }
        return m;
    }

    /** 合并留痕分页列表（含迁移行数汇总，供前端「已合并处理」区）；pair 双侧不可读的行不返回。 */
    @GetMapping("/merges")
    @RequirePerm("customer:merge")
    public Page<Map<String, Object>> list(@PageableDefault(size = 20) Pageable pageable,
                                          @RequestParam(required = false) String status) {
        return mergeService.list(status, pageable).map(this::toRow);
    }

    /** EXECUTE 审计（§4.1 step 8）：payload 含 mergeId/masterId/mergedId/groupType/movedCounts/survivorFields。 */
    private void recordExecute(CustomerMerge m, MergeExecuteService.ExecuteResult er) {
        audit.record("CUSTOMER_MERGE", m.getMergeId(), DataScope.currentActor(), "EXECUTE",
                "{\"mergeId\":\"" + esc(m.getMergeId())
                        + "\",\"masterId\":\"" + esc(m.getMasterId())
                        + "\",\"mergedId\":\"" + esc(m.getMergedId())
                        + "\",\"groupType\":\"" + esc(m.getGroupType())
                        + "\",\"movedCounts\":" + er.movedCountsJson(om)
                        + ",\"survivorFields\":" + er.survivorFieldsJson(om)
                        + "}");
    }

    private Map<String, Object> toRow(MergeExecuteService.MergeRowDTO r) {
        CustomerMerge m = r.merge();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mergeId", m.getMergeId());
        row.put("masterId", m.getMasterId());
        row.put("masterName", r.masterName());
        row.put("mergedId", m.getMergedId());
        row.put("mergedName", r.mergedName());
        row.put("groupType", m.getGroupType());
        row.put("matchReasons", m.getMatchReasons());
        row.put("score", m.getScore());
        row.put("reason", m.getReason());
        row.put("status", m.getStatus());
        row.put("requestedBy", m.getRequestedBy());
        row.put("approvedBy", m.getApprovedBy());
        row.put("approvedAt", m.getApprovedAt());
        row.put("executedAt", m.getExecutedAt());
        row.put("createdAt", m.getCreatedAt());
        row.put("movedTotal", r.movedTotal());
        return row;
    }

    /** 审计 payload 转义（与 CustomerController.esc 同款）。 */
    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
