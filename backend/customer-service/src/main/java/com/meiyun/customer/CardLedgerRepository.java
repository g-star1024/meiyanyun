package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CardLedgerRepository extends JpaRepository<CardLedger, Long> {

    /** 卡流水：按卡号时间正序（账龄顺序），卡详情「储值流水」读模型。 */
    List<CardLedger> findByCardNoOrderByLedgerIdAsc(String cardNo);

    /** 幂等查询：按来源单号查流水（充值 RC 单号重放 / 内部卡扣订单号重放防双扣）。 */
    Optional<CardLedger> findFirstByBizRef(String bizRef);

    /** B6 双账核对：按一批来源单号（WO 划扣单号）批量拉流水，txn 回填/核对用。 */
    List<CardLedger> findByBizRefIn(List<String> bizRefs);

    /** 订单退款回加幂等：按退款单号（bizRef=RF…）查已回加流水，终审重试重放不双加。 */
    Optional<CardLedger> findFirstByBizRefAndChangeType(String bizRef, String changeType);

    /** 查某卡某订单的原始储值扣款流水（CONSUME），回加时定位原卡/原扣额。 */
    Optional<CardLedger> findFirstByCardNoAndOrderNoAndChangeType(String cardNo, String orderNo, String changeType);

    /** 某卡某订单累计「退款回加」金额（REFUND 正额之和，单位分）；无记录为 0，供防超退校验。 */
    @Query(value = "select coalesce(sum(amount), 0) from card_ledger "
            + "where card_no = :cardNo and order_no = :orderNo and change_type = 'REFUND' and amount > 0",
            nativeQuery = true)
    long sumRefundedByOrder(@Param("cardNo") String cardNo, @Param("orderNo") String orderNo);

    /** 当日充值号最大序号（biz_ref 形如 RC20260905-000001，序号从第 12 位起 6 位；与 txn 各单号同口径）。 */
    @Query(value = "select coalesce(max(cast(substring(biz_ref from 12) as bigint)), 0) "
            + "from card_ledger where biz_ref like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
