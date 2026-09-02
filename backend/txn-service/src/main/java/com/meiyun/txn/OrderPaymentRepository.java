package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 订单支付明细流水仓储（order_payment）。业务表 JPA 自动建表，无物理外键。
 */
public interface OrderPaymentRepository extends JpaRepository<OrderPayment, String> {

    /** 某订单的全部支付流水（按时间正序，组合支付/部分收款展示用）。 */
    List<OrderPayment> findByOrderNoOrderByPaymentIdAsc(String orderNo);

    /** 一批订单的支付流水（防 N+1，调用方按 orderNo 归组）。 */
    List<OrderPayment> findByOrderNoInOrderByPaymentIdAsc(List<String> orderNos);

    /** 生成当日不重号支付流水号：PM + yyyyMMdd + - + 6 位序号（序号从第 12 位起）。 */
    @Query(value = "select coalesce(max(cast(substring(payment_id from 12) as bigint)),0) "
            + "from order_payment where payment_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
