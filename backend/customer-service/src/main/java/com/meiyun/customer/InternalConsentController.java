package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部端点：隐私同意状态查询（P5-B58 卡3，供 marketing-service 推送前撤回联动校验）。
 *
 * <p>红线边界（与 InternalProfileController 同款）：consent 状态是客户隐私属性，仅以系统身份
 * （X-Internal-Token，perms=["*"]，持 {@code internal:customer-directory}）开放；
 * marketing-service 推送前调 {@link #consentStatus(String)} 查询，撤回则跳过推送。
 *
 * <p>设计文档 §3.3 撤回联动：撤回后 consent_withdrawn_at 非 NULL 且 > consent_at → 跳过推送，
 * DeliveryResult.skipped("客户已撤回同意")。本端点只提供状态查询，跳过逻辑由 marketing-service 实现。
 */
@RestController
@RequestMapping("/api/customer/internal/consent")
public class InternalConsentController {

    private final ConsentService consentService;

    public InternalConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * 查询客户同意状态：GET /api/customer/internal/consent/{customerId}。
     * 客户不存在返回 404（marketing-service 侧降级为不可推送）。
     * 返回 consent_version/consent_at/consent_withdrawn_at，由调用方判断是否撤回。
     */
    @GetMapping("/{customerId}")
    @RequirePerm("internal:customer-directory")
    public ConsentService.ConsentStatus consentStatus(@PathVariable("customerId") String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new CardLedgerService.BadReq("客户ID不能为空");
        }
        ConsentService.ConsentStatus s = consentService.status(customerId);
        if (s == null) {
            throw new CardLedgerService.NotFound("客户不存在: " + customerId);
        }
        return s;
    }
}
