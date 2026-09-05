package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * B5 财务三页（预算/发票/财务设置）HTTP 端点。
 *
 * <p>类级 {@code finance:view} 兜底只读；写端点以方法级注解覆盖：
 * 预算 finance:budget:edit、发票开具/作废 finance:invoice:edit、红冲 finance:invoice:approve（双签）、
 * 设置 finance:settings:edit。全部薄调 {@link FinConfigService}，写接口四件套（校验/幂等/审计/中文错误）
 * 收敛在 Service。金额视图「元」、DB「分」。
 */
@RestController
@RequestMapping("/api/finance")
@RequirePerm("finance:view")
public class FinConfigController {

    private final FinConfigService configService;

    public FinConfigController(FinConfigService configService) {
        this.configService = configService;
    }

    // ==================== 预算 ====================

    /** 年度预算（元）。year 缺省当前年。 */
    @GetMapping("/budgets")
    @RequirePerm("finance:budget:view")
    public Map<String, Object> budgets(@RequestParam(required = false) Integer year) {
        return configService.listBudgets(year);
    }

    /** 批量保存年度预算（年+科目幂等 upsert），body：{ year?, budgets: { 科目码: 元 } }。 */
    @PutMapping("/budgets")
    @RequirePerm("finance:budget:edit")
    public Map<String, Object> saveBudgets(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> budgets = (Map<String, Object>) body.get("budgets");
        Integer year = body.get("year") == null ? null : Integer.valueOf(String.valueOf(body.get("year")));
        return configService.saveBudgets(year, budgets, SecurityContext.currentStaffName());
    }

    // ==================== 发票 ====================

    /** 发票列表（元，门店中文名）；数据域强制收敛，可按 storeCode/status/type/keyword 过滤。 */
    @GetMapping("/invoices")
    @RequirePerm("finance:invoice:view")
    public List<Map<String, Object>> invoices(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {
        return configService.listInvoices(storeCode, status, type, keyword);
    }

    /** 新建发票草稿（票号/税额服务端生成，idem_key 幂等）。 */
    @PostMapping("/invoices")
    @RequirePerm("finance:invoice:edit")
    public Map<String, Object> createInvoice(@RequestBody Map<String, Object> body) {
        return configService.createInvoice(body, SecurityContext.currentStaffName());
    }

    /** 开具：DRAFT → ISSUED。 */
    @PostMapping("/invoices/{id}/issue")
    @RequirePerm("finance:invoice:edit")
    public Map<String, Object> issueInvoice(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String reviewer = body == null ? null : String.valueOf(body.getOrDefault("reviewer", ""));
        return configService.issue(id, reviewer, SecurityContext.currentStaffName());
    }

    /** 作废：ISSUED → VOIDED（需 reason）。 */
    @PostMapping("/invoices/{id}/void")
    @RequirePerm("finance:invoice:edit")
    public Map<String, Object> voidInvoice(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        return configService.voidInvoice(id, reason, SecurityContext.currentStaffName());
    }

    /** 红冲：ISSUED → RED_FLUSHED（finance:invoice:approve 双签，需 reason）。 */
    @PostMapping("/invoices/{id}/red-flush")
    @RequirePerm("finance:invoice:approve")
    public Map<String, Object> redFlushInvoice(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String reason = body == null ? null : String.valueOf(body.getOrDefault("reason", ""));
        return configService.redFlush(id, reason, SecurityContext.currentStaffName());
    }

    // ==================== 财务设置 ====================

    /** 设置 + 科目启用表 + 最近变更日志（行不存在回落内置默认）。 */
    @GetMapping("/settings")
    @RequirePerm("finance:settings:view")
    public Map<String, Object> settings() {
        return configService.getSettings();
    }

    /** 保存设置（逐字段 diff 落日志 + 科目开关 + 全审计）。 */
    @PutMapping("/settings")
    @RequirePerm("finance:settings:edit")
    public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body) {
        return configService.saveSettings(body, SecurityContext.currentStaffName());
    }
}
