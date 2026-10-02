package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务间内部端点（棒⑤卡3 L161）：txn 计价订单免费护理免单联动——订单级整笔核销免费护理次数。
 *
 * <p>红线边界：权益钱包/核销流水属客户域私产，仅以系统身份（X-Internal-Token，perms=["*"]）开放；
 * 普通登录人无 {@code internal:benefit-write} 权限 → 403。交易域不直写权益钱包与核销流水，
 * 由客户域按自身实体语义提供订单级幂等动作，拆库后零改动成立。
 */
@RestController
@RequestMapping("/api/customer/internal/benefits")
public class InternalBenefitController {

    private final CustomerRepository customerRepo;
    private final BenefitService benefitService;

    public InternalBenefitController(CustomerRepository customerRepo, BenefitService benefitService) {
        this.customerRepo = customerRepo;
        this.benefitService = benefitService;
    }

    /**
     * 订单级免费护理核销（txn 计价免单联动，先扣后生）：POST /api/customer/internal/benefits/order-consume。
     * body{customerId, orderNo, projectNames[]}（一项目扣一次）；orderNo 为订单级幂等锚——
     * 重放返既有流水零副作用；无权益/任一项目次数不足 422 中文整笔回滚（不落异常流水，
     * txn 侧透传后整笔订单回滚）；客户不存在 404、参数非法 400 中文。
     */
    @PostMapping("/order-consume")
    @RequirePerm("internal:benefit-write")
    public Map<String, Object> orderConsume(@RequestBody Cmd cmd) {
        if (cmd == null) throw new CustomerService.BadReq("请求体不能为空");
        if (cmd.customerId() == null || cmd.customerId().isBlank()) {
            throw new CustomerService.BadReq("客户ID不能为空");
        }
        Customer customer = customerRepo.findById(cmd.customerId().trim())
                .orElseThrow(() -> new CustomerService.NotFound("客户不存在: " + cmd.customerId().trim()));
        List<BenefitService.WriteoffResult> flows =
                benefitService.writeoffForOrder(customer, cmd.orderNo(), cmd.projectNames());
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("orderNo", cmd.orderNo() == null ? "" : cmd.orderNo().trim());
        resp.put("flowCount", flows.size());
        resp.put("writeoffNos", flows.stream().map(BenefitService.WriteoffResult::writeoffNo).toList());
        resp.put("remaining", flows.isEmpty() ? null : flows.get(flows.size() - 1).remaining());
        return resp;
    }

    /** 订单核销入参：projectNames 为本次免单联动核销的护理项目列表（一项目扣一次）。 */
    public record Cmd(String customerId, String orderNo, List<String> projectNames) {}
}
