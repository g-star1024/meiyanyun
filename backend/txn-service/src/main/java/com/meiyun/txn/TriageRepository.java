package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TriageRepository extends JpaRepository<Triage, String> {

    /** 一条到店登记对应当前分诊单（一对一 upsert）。 */
    Optional<Triage> findByArrivalId(String arrivalId);

    /** 列表批量富化用：按到店登记号集合一次拉全。 */
    List<Triage> findByArrivalIdIn(List<String> arrivalIds);

    /** 当日单号最大序号：TR + yyyyMMdd + - + 6 位，序号从第 12 位起。 */
    @Query(value = "select coalesce(max(cast(substring(tr_no from 12) as bigint)), 0) " +
            "from triage where tr_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
