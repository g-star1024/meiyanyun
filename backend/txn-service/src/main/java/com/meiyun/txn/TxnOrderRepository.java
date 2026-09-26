package com.meiyun.txn;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TxnOrderRepository extends JpaRepository<TxnOrder, String>, JpaSpecificationExecutor<TxnOrder> {

    /** 客户 360：按客户列订单（时间倒序）。 */
    List<TxnOrder> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    /** 提成业绩聚合：按订单号批量取订单（划扣/退款记录本身无顾问字段，需 JOIN 订单取 consultant）。 */
    List<TxnOrder> findByOrderNoIn(Collection<String> orderNos);

    /** 收银台/订单页：按状态分页（创建时间倒序）。 */
    Page<TxnOrder> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** 按门店 + 状态分页。 */
    Page<TxnOrder> findByStoreCodeAndStatusOrderByCreatedAtDesc(String storeCode, String status, Pageable pageable);

    /** 按门店分页（不限状态）。 */
    Page<TxnOrder> findByStoreCodeOrderByCreatedAtDesc(String storeCode, Pageable pageable);

    /** 全量分页（创建时间倒序）。 */
    Page<TxnOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 当日订单号最大序号（order_no 形如 OD20260901-000007，序号从第 12 位起 6 位），用于生成不重号的下一个号。 */
    @Query(value = "select coalesce(max(cast(substring(order_no from 12) as bigint)), 0) " +
           "from txn_order where order_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);

    /** B99 转化漏斗成交级：区间已收款/已核销订单的成交客户数（客户级去重，按门店）。 */
    @Query("SELECT o.storeCode, COUNT(DISTINCT o.customerId) FROM TxnOrder o " +
           "WHERE o.createdAt >= :from AND o.createdAt < :to AND o.status IN ('已收款','已核销') " +
           "GROUP BY o.storeCode")
    List<Object[]> funnelDealCustomers(@Param("from") java.time.OffsetDateTime from,
                                       @Param("to") java.time.OffsetDateTime to);

    /** B99 转化漏斗成交/复购级：区间已收款/已核销订单行（customerId, storeCode）（成交去重/复购≥2 由调用方内存聚合——JPQL 不支持 FROM 子查询）。 */
    @Query("SELECT o.customerId, o.storeCode FROM TxnOrder o " +
           "WHERE o.createdAt >= :from AND o.createdAt < :to AND o.status IN ('已收款','已核销')")
    List<Object[]> funnelPaidOrderRows(@Param("from") java.time.OffsetDateTime from,
                                       @Param("to") java.time.OffsetDateTime to);
}
