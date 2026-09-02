package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TxnCardCancelRepository extends JpaRepository<TxnCardCancel, String>, JpaSpecificationExecutor<TxnCardCancel> {

    List<TxnCardCancel> findAllByOrderByTxnNoDesc();

    List<TxnCardCancel> findByStatusOrderByTxnNoDesc(String status);

    /** 当日退卡号最大序号（txn_no 形如 CC20260901-000007，序号从第 12 位起 6 位；历史 TK 前缀按 CC 独立序列）。 */
    @Query(value = "select coalesce(max(cast(substring(txn_no from 12) as bigint)), 0) " +
           "from txn_card_cancel where txn_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
