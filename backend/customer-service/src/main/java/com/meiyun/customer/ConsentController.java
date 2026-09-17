package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 隐私同意生命周期端点（PIPL 第 14-16 条同意要件 + 第 15 条撤回权）。
 * 详见 docs/DESIGN-P5-B57-CARD3-COMPLIANCE-2026-09-17.md §3。
 *
 * <p>挂 /api/customer（铁律 1：网关 /api/customer 前缀已路由到 customer-service:8082）。
 * 权限码复用 dsar:view/dsar:edit（consent 属隐私合规范畴，与 DSAR 同权限，不新增 consent:edit）。
 *
 * <p>三端点：
 * <ul>
 *   <li>POST /api/customer/{id}/consent/grant?scene=MARKETING — 授权同意（dsar:edit）</li>
 *   <li>POST /api/customer/{id}/consent/withdraw — 撤回同意（dsar:edit）</li>
 *   <li>GET  /api/customer/{id}/consent — 查询同意状态（dsar:view）</li>
 * </ul>
 *
 * <p>每次同意/撤回均落 CONSENT/GRANT 或 CONSENT/WITHDRAW 审计（payload 含 version+scene）。
 * 门店越权：客户非本店不可读（404 中文，不泄露数据是否存在）。
 */
@RestController
@RequestMapping("/api/customer")
public class ConsentController {

    @Autowired
    private ConsentService consentService;
    @Autowired
    private CustomerRepository customerRepo;
    @Autowired
    private AuditRecorder audit;

    /**
     * 授权同意：consent_version+1, consent_at=now, consent_withdrawn_at=null。
     * scene 参数记录授权场景（REGISTER/MARKETING/APPOINTMENT/PRESCRIPTION），仅落审计 payload。
     * 幂等：已同意且未撤回时返回当前态（service 层不重复 +1，Controller 层也不重复记审计）。
     */
    @PostMapping("/{id}/consent/grant")
    @RequirePerm("dsar:edit")
    public Customer grantConsent(@PathVariable("id") String id,
                                 @RequestParam(value = "scene", defaultValue = "MARKETING") String scene) {
        Customer before = requireReadable(id);
        int beforeVersion = before.getConsentVersion() == null ? 0 : before.getConsentVersion();
        boolean withdrawn = before.getConsentWithdrawnAt() != null
                && before.getConsentAt() != null
                && before.getConsentWithdrawnAt().isAfter(before.getConsentAt());
        Customer updated = consentService.grant(id, scene);
        int afterVersion = updated.getConsentVersion() == null ? 0 : updated.getConsentVersion();
        // 仅在实际变更（version 递增或清空撤回）时落审计
        if (afterVersion > beforeVersion || withdrawn) {
            audit.record("CONSENT", id, DataScope.currentActor(), "GRANT",
                    "{\"customerId\":\"" + esc(id)
                            + "\",\"version\":" + afterVersion
                            + ",\"scene\":\"" + esc(scene) + "\"}");
        }
        return updated;
    }

    /**
     * 撤回同意：consent_withdrawn_at=now。幂等：已撤回时返回当前态（不重复记审计）。
     * 撤回后 marketing-service 推送前调内部端点查询，已撤回则跳过推送。
     */
    @PostMapping("/{id}/consent/withdraw")
    @RequirePerm("dsar:edit")
    public Customer withdrawConsent(@PathVariable("id") String id) {
        Customer before = requireReadable(id);
        boolean alreadyWithdrawn = before.getConsentWithdrawnAt() != null
                && before.getConsentAt() != null
                && before.getConsentWithdrawnAt().isAfter(before.getConsentAt());
        Customer updated = consentService.withdraw(id);
        // 仅在实际变更（本次新落撤回时间）时落审计
        if (!alreadyWithdrawn) {
            audit.record("CONSENT", id, DataScope.currentActor(), "WITHDRAW",
                    "{\"customerId\":\"" + esc(id)
                            + "\",\"version\":" + (updated.getConsentVersion() == null ? 0 : updated.getConsentVersion())
                            + ",\"withdrawnAt\":\"" + (updated.getConsentWithdrawnAt() == null ? "" : updated.getConsentWithdrawnAt().toString())
                            + "\"}");
        }
        return updated;
    }

    /**
     * 查询同意状态：返回 consent_version/consent_at/consent_withdrawn_at（dsar:view 权限）。
     * 前端客户档案「隐私请求」tab 旁显示同意状态徽章（已同意/已撤回/未授权）。
     */
    @GetMapping("/{id}/consent")
    @RequirePerm("dsar:view")
    public ConsentService.ConsentStatus getConsent(@PathVariable("id") String id) {
        requireReadable(id);
        ConsentService.ConsentStatus s = consentService.status(id);
        if (s == null) throw new CustomerService.NotFound("数据不存在或无权查看");
        return s;
    }

    /**
     * 客户数据域断言：不存在或越权统一 404，不泄露数据是否存在（与 CustomerController.requireReadable 同款）。
     */
    private Customer requireReadable(String id) {
        Customer c = customerRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        return c;
    }

    /** 审计 payload 转义（与 CustomerController.esc 同款）。 */
    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
