package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 进项发票 HTTP 端点（B63 卡4 L86，{@code /api/finance/input-invoices}）。
 *
 * <p>类级 {@code finance:view} 兜底只读；方法级三细码覆盖：
 * 查询 finance:input:view、登记/标记不抵扣 finance:input:edit、
 * 用途确认/撤销/抵扣/进项转出 finance:input:confirm；汇总走 finance:tax:view。
 * 薄调 {@link InputInvoiceService}，校验/幂等/状态机/审计/中文错误收敛在 Service。
 */
@RestController
@RequestMapping("/api/finance/input-invoices")
@RequirePerm("finance:view")
public class InputInvoiceController {

    private final InputInvoiceService service;

    public InputInvoiceController(InputInvoiceService service) {
        this.service = service;
    }

    /** 进项发票分页（元）；可按 storeCode/status/invoiceKind/purpose/periodId/keyword 过滤。 */
    @GetMapping
    @RequirePerm("finance:input:view")
    public Page<Map<String, Object>> list(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String invoiceKind,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return service.list(storeCode, status, invoiceKind, purpose, periodId, keyword, page, size);
    }

    /** 进项抵扣汇总（元，含期末留抵）；periodId 优先，其次 type＋period，缺省当前月开放期。 */
    @GetMapping("/summary")
    @RequirePerm("finance:tax:view")
    public Map<String, Object> summary(
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String period) {
        return service.summary(periodId, type, period);
    }

    /** 登记进项发票（UNCONFIRMED；登记号/税额服务端生成，idem_key 重复 409 中文）。 */
    @PostMapping
    @RequirePerm("finance:input:edit")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return service.register(body, SecurityContext.currentStaffName());
    }

    /** 进项发票详情（元）。 */
    @GetMapping("/{id}")
    @RequirePerm("finance:input:view")
    public Map<String, Object> get(@PathVariable Long id) {
        return service.get(id);
    }

    /** 用途确认：body purpose=DEDUCT/NO_DEDUCT/REFUND；NO_DEDUCT 须带 reason 七码。 */
    @PostMapping("/{id}/confirm")
    @RequirePerm("finance:input:confirm")
    public Map<String, Object> confirm(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return service.confirm(id, body, SecurityContext.currentStaffName());
    }

    /** 撤销用途确认：仅 CONFIRMED 可回 UNCONFIRMED（已抵扣 422 中文）。 */
    @PostMapping("/{id}/revoke-confirm")
    @RequirePerm("finance:input:confirm")
    public Map<String, Object> revokeConfirm(@PathVariable Long id) {
        return service.revokeConfirm(id, SecurityContext.currentStaffName());
    }

    /** 抵扣：CONFIRMED＋DEDUCT → DEDUCTED；body periodId 可缺省（懒创建当前月 OPEN 期）。 */
    @PostMapping("/{id}/deduct")
    @RequirePerm("finance:input:confirm")
    public Map<String, Object> deduct(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return service.deduct(id, body, SecurityContext.currentStaffName());
    }

    /** 进项转出：DEDUCTED → TRANSFERRED_OUT；body amount(元)、reason 七码、remark 选填。 */
    @PostMapping("/{id}/transfer-out")
    @RequirePerm("finance:input:confirm")
    public Map<String, Object> transferOut(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return service.transferOut(id, body, SecurityContext.currentStaffName());
    }

    /** 标记不抵扣：UNCONFIRMED → NON_DEDUCTIBLE 终态旁路；body reason 七码、remark 选填。 */
    @PostMapping("/{id}/non-deductible")
    @RequirePerm("finance:input:edit")
    public Map<String, Object> nonDeductible(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return service.nonDeductible(id, body, SecurityContext.currentStaffName());
    }
}
