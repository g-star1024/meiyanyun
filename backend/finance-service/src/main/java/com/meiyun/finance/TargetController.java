package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * B49 卡8 目标管理 HTTP 端点（M1 集团屏 /m1-target）。
 *
 * <p>类级 {@code target:view} 兜底只读；写端点以方法级注解覆盖：
 * 新建/进度更新/提交 target:edit，批准/驳回 target:approve（双签语义）。
 * 全部薄调 {@link BizTargetService}，写接口四件套（校验/幂等/审计/中文错误）收敛在 Service。
 * 权限矩阵已备（target:view/edit/approve 多角色），本卡不动矩阵。
 */
@RestController
@RequestMapping("/api/finance/targets")
@RequirePerm("target:view")
public class TargetController {

    private final BizTargetService targetService;

    public TargetController(BizTargetService targetService) {
        this.targetService = targetService;
    }

    /** 目标列表（ownerType/metric/period/approval 可选过滤）。 */
    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String approval) {
        return targetService.list(ownerType, metric, period, approval);
    }

    /** 新建目标（默认 DRAFT，idemKey 幂等；targetId 缺省服务端生成）。 */
    @PostMapping
    @RequirePerm("target:edit")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return targetService.create(body, SecurityContext.currentStaffName());
    }

    /** 更新当前进度（仅 APPROVED 可更新）。body：{ value }。 */
    @PutMapping("/{targetId}/progress")
    @RequirePerm("target:edit")
    public Map<String, Object> updateProgress(@PathVariable String targetId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        return targetService.updateProgress(targetId, body, SecurityContext.currentStaffName());
    }

    /** 提交审批：DRAFT → PENDING。 */
    @PostMapping("/{targetId}/submit")
    @RequirePerm("target:edit")
    public Map<String, Object> submit(@PathVariable String targetId) {
        return targetService.submit(targetId, SecurityContext.currentStaffName());
    }

    /** 批准：PENDING → APPROVED。 */
    @PostMapping("/{targetId}/approve")
    @RequirePerm("target:approve")
    public Map<String, Object> approve(@PathVariable String targetId) {
        return targetService.approve(targetId, SecurityContext.currentStaffName());
    }

    /** 驳回：PENDING → REJECTED（需 reason）。 */
    @PostMapping("/{targetId}/reject")
    @RequirePerm("target:approve")
    public Map<String, Object> reject(@PathVariable String targetId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        return targetService.reject(targetId, reason, SecurityContext.currentStaffName());
    }

    /** B69 卡1（L133）：退回修改——REJECTED → DRAFT。 */
    @PostMapping("/{targetId}/reset")
    @RequirePerm("target:approve")
    public Map<String, Object> reset(@PathVariable String targetId) {
        return targetService.reset(targetId, SecurityContext.currentStaffName());
    }
}
