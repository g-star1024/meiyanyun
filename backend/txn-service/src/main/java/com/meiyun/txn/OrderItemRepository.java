package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 订单收费子项仓储（order_item）。业务表 JPA 自动建表，无物理外键。
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 批量取一批订单的全部子项（防 N+1），调用方按 orderNo + lineNo 归组排序。 */
    List<OrderItem> findByOrderNoIn(List<String> orderNos);
}
