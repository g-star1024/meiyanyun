package com.meiyun.txn;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 随访仓储（followup）。
 */
public interface FollowupRepository extends JpaRepository<Followup, Long>, JpaSpecificationExecutor<Followup> {

    /**
     * 超期巡检限批：PENDING + SOP 节点（sop_batch_id 非空）+ plan_date 早于今日零点 + 未升级，
     * 按计划日期升序 FIFO（最早超期先升级）。
     */
    @Query("select f from Followup f where f.status = 'PENDING' and f.sopBatchId is not null "
            + "and f.planDate < :today and f.escalated = false order by f.planDate asc")
    List<Followup> findOverdueForEscalation(@Param("today") LocalDate today, Pageable pageable);

    Page<Followup> findBySopBatchIdOrderByPlanDateAsc(String sopBatchId, Pageable pageable);

    /** 多个批次的全部节点（批次看板批量装配，避免 N+1），按计划日期升序。 */
    List<Followup> findBySopBatchIdInOrderByPlanDateAsc(java.util.Collection<String> sopBatchIds);

    /**
     * 生成当日不重随访号：HF + yyyyMMdd + - + 6 位序号（序号从第 12 位起，char_length=17）。
     */
    @Query(value = "select coalesce(max(cast(substring(followup_no from 12) as bigint)),0) "
            + "from followup where followup_no like :prefix and char_length(followup_no) = 17",
            nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
