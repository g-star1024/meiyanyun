package com.meiyun.customer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * 隐私同意生命周期业务层（PIPL 第 14-16 条同意要件 + 第 15 条撤回权）。
 *
 * <p>四场景同意记录（设计文档 §3.2）：
 * <ul>
 *   <li>会员注册：CustomerController.create 成功后调 {@link #grant(String, String)}（scene=REGISTER）consent_version 0→1</li>
 *   <li>预约确认：txn-service 勾选「同意个人信息处理」（留待后续，不在本卡范围）</li>
 *   <li>处方确认：treat-done 治疗完成勾选（留待后续，不在本卡范围）</li>
 *   <li>营销推送：POST /api/customer/{id}/consent/grant（scene=MARKETING，单独授权营销推送）</li>
 * </ul>
 *
 * <p>撤回联动（设计文档 §3.3）：撤回后 consent_withdrawn_at 非 NULL 且 > consent_at，
 * marketing-service 推送前调内部端点 {@link InternalConsentController#consentStatus(String)} 查询，
 * 已撤回则跳过推送（合规拦截，不落 push_record）。
 *
 * <p>每次同意/撤回均由 Controller 层落 CONSENT/GRANT 或 CONSENT/WITHDRAW 审计（payload 含 version+scene）。
 */
@Service
public class ConsentService {

    @Autowired
    private CustomerRepository customerRepo;

    /**
     * 授权同意：consent_version+1, consent_at=now, consent_withdrawn_at=null。
     * 客户不存在 404；幂等：已同意且未撤回时返回当前态（不重复 +1）。
     *
     * @param customerId 客户编号
     * @param scene 授权场景（REGISTER/MARKETING/APPOINTMENT/PRESCRIPTION），仅记录审计 payload，不影响业务流转
     * @return 更新后的客户实体
     */
    @Transactional
    public Customer grant(String customerId, String scene) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户编号不能为空");
        }
        Customer c = customerRepo.findById(customerId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + customerId));
        // 幂等：已同意且未撤回（consent_withdrawn_at 为 null 或 < consent_at）→ 返回当前态，不重复 +1
        if (c.getConsentVersion() != null && c.getConsentVersion() > 0
                && c.getConsentAt() != null
                && (c.getConsentWithdrawnAt() == null
                        || c.getConsentWithdrawnAt().isBefore(c.getConsentAt()))) {
            return c;
        }
        // 首次同意 0→1，重新授权（撤回后再次同意）+1
        int newVersion = (c.getConsentVersion() == null ? 0 : c.getConsentVersion()) + 1;
        c.setConsentVersion(newVersion);
        c.setConsentAt(OffsetDateTime.now());
        c.setConsentWithdrawnAt(null);  // 重新授权清空撤回时间
        return customerRepo.save(c);
    }

    /**
     * 撤回同意：consent_withdrawn_at=now。客户不存在 404；幂等：已撤回返回当前态。
     * 撤回后 consent_withdrawn_at 非 NULL 且 > consent_at（marketing 推送前硬校验）。
     *
     * @param customerId 客户编号
     * @return 更新后的客户实体
     */
    @Transactional
    public Customer withdraw(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户编号不能为空");
        }
        Customer c = customerRepo.findById(customerId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + customerId));
        // 幂等：已撤回（consent_withdrawn_at 非 null 且 > consent_at）→ 返回当前态
        if (c.getConsentWithdrawnAt() != null && c.getConsentAt() != null
                && c.getConsentWithdrawnAt().isAfter(c.getConsentAt())) {
            return c;
        }
        // 未同意（version=0）不可撤回
        if (c.getConsentVersion() == null || c.getConsentVersion() == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户尚未授权同意，不可撤回");
        }
        c.setConsentWithdrawnAt(OffsetDateTime.now());
        return customerRepo.save(c);
    }

    /**
     * 查询同意状态（不抛异常，供内部端点跨服务调用）。
     * 客户不存在返回 null（由调用方降级处理）。
     */
    @Transactional(readOnly = true)
    public ConsentStatus status(String customerId) {
        if (customerId == null || customerId.isBlank()) return null;
        return customerRepo.findById(customerId.trim())
                .map(c -> new ConsentStatus(
                        c.getCustomerId(),
                        c.getConsentVersion() == null ? 0 : c.getConsentVersion(),
                        c.getConsentAt(),
                        c.getConsentWithdrawnAt()))
                .orElse(null);
    }

    /**
     * 同意状态投影（供 marketing-service 跨服务查询）。
     *
     * @param customerId 客户编号
     * @param consentVersion 同意版本号（0=未同意，≥1=已同意）
     * @param consentAt 最近一次同意时间（未同意为 null）
     * @param consentWithdrawnAt 最近一次撤回时间（未撤回为 null）
     */
    public record ConsentStatus(String customerId, Integer consentVersion,
                                OffsetDateTime consentAt, OffsetDateTime consentWithdrawnAt) {
    }
}
