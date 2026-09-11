package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 术后 SOP 排程批次仓储（followup_sop_batch）。
 */
public interface FollowupSopBatchRepository extends JpaRepository<FollowupSopBatch, String>,
        JpaSpecificationExecutor<FollowupSopBatch> {

    /** 方案单维度幂等：一张方案单只排一次术后 SOP。 */
    boolean existsBySourcePlanId(String sourcePlanId);

    /**
     * 生成当日不重批次号：SOP + yyyyMMdd + - + 6 位序号（序号从第 13 位起，char_length=18）。
     */
    @Query(value = "select coalesce(max(cast(substring(batch_no from 13) as bigint)),0) "
            + "from followup_sop_batch where batch_no like :prefix and char_length(batch_no) = 18",
            nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
