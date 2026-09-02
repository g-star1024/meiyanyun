package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WriteoffRepository extends JpaRepository<WriteoffRecord, String>, JpaSpecificationExecutor<WriteoffRecord> {
    List<WriteoffRecord> findAllByOrderByCreatedAtDesc();

    /** 订单核销判重：该订单是否已生成订单维度核销记录（card_no 为空即订单整单核销）。 */
    boolean existsByOrderNoAndCardNoIsNull(String orderNo);

    /** 订单核销记录（card_no 为空），按状态过滤、时间倒序。 */
    List<WriteoffRecord> findByCardNoIsNullAndStatusOrderByCreatedAtDesc(String status);

    /** 全部订单核销记录（card_no 为空），时间倒序。 */
    List<WriteoffRecord> findByCardNoIsNullOrderByCreatedAtDesc();

    /** 当日核销号最大序号（writeoff_id 形如 WO20260901-000007，序号从第 12 位起 6 位）。 */
    @Query(value = "select coalesce(max(cast(substring(writeoff_id from 12) as bigint)), 0) " +
           "from writeoff_record where writeoff_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
