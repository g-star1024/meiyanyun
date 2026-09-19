package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface FundEntryRepository extends JpaRepository<FundEntry, Long> {

    Optional<FundEntry> findByIdemKey(String idemKey);

    List<FundEntry> findByOccurredAtBetweenOrderByOccurredAtAsc(OffsetDateTime from, OffsetDateTime to);

    /** B11 重算本月：删除该月（occurredAt 落入 UTC 半开月区间）系统结转分录（CARRY 幂等键），可按门店收敛。 */
    @Modifying
    @Query(value = "delete from fund_entry where source = 'SYSTEM' and idem_key like 'CARRY:%' " +
            "and occurred_at >= :from and occurred_at < :to " +
            "and (:storeCode = '' or store_code = :storeCode)", nativeQuery = true)
    int deleteSystemCarry(@Param("from") OffsetDateTime from,
                          @Param("to") OffsetDateTime to,
                          @Param("storeCode") String storeCode);

    /** B11 封账前置/测算：已结转的幂等键集合（CARRY:%，该月），用于判定「本月哪些规则×门店已结转」。 */
    @Query(value = "select idem_key from fund_entry where idem_key like 'CARRY:%' " +
            "and occurred_at >= :from and occurred_at < :to", nativeQuery = true)
    List<String> findCarryIdemKeys(@Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to);

    /**
     * B11 重算删除前收集：该月曾有 SYSTEM 结转分录的门店集合（删除前调用）。
     * 用于删除后对这些门店补刷月报——成本归零的门店删除后不再触发落账钩子，
     * 不补刷会让 revenue_monthly 停在旧值。
     */
    @Query(value = "select distinct store_code from fund_entry where source = 'SYSTEM' and idem_key like 'CARRY:%' " +
            "and occurred_at >= :from and occurred_at < :to and store_code is not null", nativeQuery = true)
    List<String> findCarryStoreCodes(@Param("from") OffsetDateTime from,
                                     @Param("to") OffsetDateTime to);

    /**
     * B63 卡3 预收账龄扫描投影：按门店聚合 RF-DEPOSIT 净沉淀（IN 充值 − OUT 核销/退卡冲回）
     * 与「仍有净沉淀前提下最早一笔充值发生时间」，仅取发生时间早于账龄截止线（:cutoff）的门店。
     * 调用方以净沉淀 &gt; 0 且最早充值距今 ≥ 阈值天作为命中条件；cutoff 由调用方按 Asia/Shanghai
     * 业务日换算为 OffsetDateTime 传入，SQL 本身只做半开上界比较。
     */
    @Query(value = "select store_code as storeCode, " +
            "sum(case when direction = 'IN' then amount else -amount end) as netFen, " +
            "min(case when direction = 'IN' then occurred_at end) as oldestInAt " +
            "from fund_entry where subject = 'RF-DEPOSIT' and store_code is not null " +
            "and occurred_at < :cutoff " +
            "group by store_code", nativeQuery = true)
    List<DepositAgeRow> depositAgeScan(@Param("cutoff") OffsetDateTime cutoff);

    /** 预收账龄扫描行投影：门店码 / RF-DEPOSIT 净沉淀（分）/ 最早一笔 IN 的发生时间（可空）。 */
    interface DepositAgeRow {
        String getStoreCode();
        Long getNetFen();
        java.time.OffsetDateTime getOldestInAt();
    }
}
