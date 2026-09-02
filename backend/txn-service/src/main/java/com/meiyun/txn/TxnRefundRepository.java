package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TxnRefundRepository extends JpaRepository<TxnRefund, String>, JpaSpecificationExecutor<TxnRefund> {

    List<TxnRefund> findAllByOrderByTxnNoDesc();

    List<TxnRefund> findByStatusOrderByTxnNoDesc(String status);

    /** 当日退款号最大序号（txn_no 形如 RF20260901-000007，序号从第 12 位起 6 位）。 */
    @Query(value = "select coalesce(max(cast(substring(txn_no from 12) as bigint)), 0) " +
           "from txn_refund where txn_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
