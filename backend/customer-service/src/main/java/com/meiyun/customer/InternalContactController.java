package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部端点：客户明文联系方式查询（棒⑧卡2，供 marketing-service 短信外发取收件号码）。
 *
 * <p>红线边界（与 InternalConsentController 同款）：明文手机号是客户隐私属性，仅以系统身份
 * （X-Internal-Token，perms=["*"]，持 {@code internal:customer-directory}）开放；
 * 现有 phone-map 端点仅返回掩码（138****2046）不可用于外发，本端点专供外发腿取号。
 * 前端永不暴露本端点明文。
 */
@RestController
@RequestMapping("/api/customer/internal/contact")
public class InternalContactController {

    private final CustomerRepository customerRepository;

    public InternalContactController(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    /** 联系方式投影（仅供服务间外发取号）。 */
    public record ContactView(String customerId, String phone) {
    }

    /**
     * 查询客户明文手机号：GET /api/customer/internal/contact/{customerId}。
     * 客户不存在返回 404（marketing-service 侧软降级 SKIPPED 不外发）。
     */
    @GetMapping("/{customerId}")
    @RequirePerm("internal:customer-directory")
    public ContactView contact(@PathVariable("customerId") String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new CardLedgerService.BadReq("客户ID不能为空");
        }
        String id = customerId.trim();
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new CardLedgerService.NotFound("客户不存在: " + id));
        return new ContactView(id, c.getPhone());
    }
}
