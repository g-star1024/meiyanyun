package com.meiyun.audit;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * B49 卡9 合规中心 HTTP 端点（M1 集团屏 /m1-compliance）。
 *
 * <p>挂 {@code /api/audit/compliance} 下，复用网关既有 /api/audit → audit-service 路由，
 * 零网关/nginx 改动。类级 {@code compliance:view} 兜底只读；复检写端点以方法级
 * {@code compliance:edit} 覆盖（照卡8 TargetController 先例）。权限矩阵已备
 * （compliance:view/edit 多角色），本卡不动矩阵。
 * 全部薄调 {@link ComplianceService}，写接口校验/审计收敛在 Service。
 */
@RestController
@RequestMapping("/api/audit/compliance")
@RequirePerm("compliance:view")
public class ComplianceController {

    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    /** 检查项列表（category/status 可选过滤，按 id 升序）。 */
    @GetMapping("/checks")
    public List<ComplianceCheck> checks(@RequestParam(required = false) String category,
                                        @RequestParam(required = false) String status) {
        return complianceService.list(category, status);
    }

    /** 复检：body={pass:boolean 必填, remark?}，同事务写 COMPLIANCE/RECHECK 审计。 */
    @PostMapping("/checks/{id}/recheck")
    @RequirePerm("compliance:edit")
    public ComplianceCheck recheck(@PathVariable Long id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("pass");
        Boolean pass = raw instanceof Boolean b ? b : null;
        Object r = body == null ? null : body.get("remark");
        String remark = r == null ? null : String.valueOf(r);
        return complianceService.recheck(id, pass, remark, SecurityContext.currentStaffName());
    }
}
