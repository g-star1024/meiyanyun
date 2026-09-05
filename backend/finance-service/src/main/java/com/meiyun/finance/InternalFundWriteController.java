package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * finance 内部写端点（服务间，X-Internal-Token 系统身份）。
 *
 * <p>红线：与面向财务页面的 {@link FinanceController}（类级 finance:view 全只读）隔离为独立类，
 * 本类所有端点要求内部权限码 {@code internal:fund-write}——仅 X-Internal-Token 系统身份（perms=["*"]）放行，
 * 普通 JWT 登录人无此权限码 → 403。网络隔离（仅集群内可达）留待 B6 加固。
 *
 * <p>写接口四件套：参数校验（FundEntryService 422 中文）、幂等（idem_key 唯一，重放不双算）、
 * 全审计（FUND_ENTRY / FUND_RECONCILE / FUND_ADJUST 均落 audit_log）、同事务落账。
 */
@RestController
@RequestMapping("/api/finance/internal")
@RequirePerm("internal:fund-write")
public class InternalFundWriteController {

    private final FundEntryService fundEntryService;
    private final FinanceInternalOpsService internalOps;

    public InternalFundWriteController(FundEntryService fundEntryService,
                                       FinanceInternalOpsService internalOps) {
        this.fundEntryService = fundEntryService;
        this.internalOps = internalOps;
    }

    /**
     * 资金分录批量落账：POST /api/finance/internal/entries。
     * 经营域（txn）收款/退款终审/划扣完成后投递；逐条幂等，已落账分录回带 duplicated=true。
     */
    @PostMapping("/entries")
    public List<Map<String, Object>> postEntries(@RequestBody List<FundEntryCmd> cmds) {
        return fundEntryService.postEntries(cmds, SecurityContext.currentStaffId());
    }

    /**
     * outbox 对账标记：POST /api/finance/internal/reconcile/outbox/{id}。
     * body：{"diff": false, "remark": "对账一致"}；diff=true 标 DIFF 待调平，false 标 RECONCILED。
     * 非 PENDING 状态 400 中文。
     */
    @PostMapping("/reconcile/outbox/{id}")
    public Map<String, Object> reconcile(@PathVariable("id") Long id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        boolean diff = body != null && Boolean.TRUE.equals(body.get("diff"));
        String remark = body != null && body.get("remark") != null ? String.valueOf(body.get("remark")) : null;
        return fundEntryService.reconcileOutbox(id, diff, remark, SecurityContext.currentStaffId());
    }

    /**
     * 差异调平：POST /api/finance/internal/adjust/{id}。
     * body：{"direction":"IN/OUT","amountFen":100,"subject":"RF-REVENUE","channel":"cash","memo":"..."}。
     * DIFF → ADJUSTED 并补一条 ADJUST 调整分录（幂等）；非 DIFF 状态 400 中文。
     */
    @PostMapping("/adjust/{id}")
    public Map<String, Object> adjust(@PathVariable("id") Long id,
                                      @RequestBody Map<String, Object> body) {
        String direction = body.get("direction") == null ? null : String.valueOf(body.get("direction"));
        Long amountFen = body.get("amountFen") == null ? null
                : Long.valueOf(String.valueOf(body.get("amountFen")));
        String subject = body.get("subject") == null ? null : String.valueOf(body.get("subject"));
        String channel = body.get("channel") == null ? null : String.valueOf(body.get("channel"));
        String memo = body.get("memo") == null ? null : String.valueOf(body.get("memo"));
        return fundEntryService.adjustOutbox(id, direction, amountFen, subject, channel, memo,
                SecurityContext.currentStaffId());
    }

    /**
     * 双跑校对：GET /api/finance/internal/ledger-diff?from=yyyy-MM-dd&to=yyyy-MM-dd。
     * 按 subject+direction 比对「聚合净额 vs 落账净额」，matched=true（差异 0 分）方可切换读源（DESIGN §7）。
     */
    @GetMapping("/ledger-diff")
    public Map<String, Object> ledgerDiff(@RequestParam String from, @RequestParam String to) {
        return internalOps.ledgerDiff(from, to);
    }

    /**
     * 历史回填：POST /api/finance/internal/backfill?from=yyyy-MM-dd&to=yyyy-MM-dd。
     * 按交易域四表流水生成资金分录幂等落账（idem_key 去重），可重复执行，返回新增/跳过计数。
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam String from, @RequestParam String to) {
        return internalOps.backfill(from, to);
    }
}
