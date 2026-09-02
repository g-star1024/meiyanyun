package com.meiyun.txn;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TxnOrderRepository extends JpaRepository<TxnOrder, String>, JpaSpecificationExecutor<TxnOrder> {

    /** 客户 360：按客户列订单（时间倒序）。 */
    List<TxnOrder> findByCustomerIdOrderByCreatedAtDesc(String customerId);

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
}
