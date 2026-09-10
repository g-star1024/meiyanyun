package com.meiyun.customer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户检索索引事件发布器（B30）。
 *
 * <p>仅做同事务落 outbox（REQUIRED：加入业务发起方事务，一起提交/回滚）；
 * 真正的 ES 投递由 CustomerSearchEventRelayJob 近线异步完成，建档链路不被 ES 可用性拖慢或阻断。
 */
@Component
public class CustomerSearchEventPublisher {

    private final CustomerSearchEventRepository eventRepo;

    public CustomerSearchEventPublisher(CustomerSearchEventRepository eventRepo) {
        this.eventRepo = eventRepo;
    }

    /** 登记一条 UPSERT 事件（随当前事务提交）。重复登记安全——中继按 customerId 幂等 upsert。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitUpsert(String customerId) {
        if (customerId == null || customerId.isBlank()) return;
        CustomerSearchEvent e = new CustomerSearchEvent();
        e.setEventType("UPSERT");
        e.setCustomerId(customerId);
        e.setStatus("PENDING");
        eventRepo.save(e);
    }
}
