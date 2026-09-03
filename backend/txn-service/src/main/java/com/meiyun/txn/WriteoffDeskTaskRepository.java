package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WriteoffDeskTaskRepository
        extends JpaRepository<WriteoffDeskTask, String>, JpaSpecificationExecutor<WriteoffDeskTask> {

    /** 签到自动建任务幂等：同预约号已有任务（不限状态）即不重复建。 */
    List<WriteoffDeskTask> findByApptNo(String apptNo);

    /** 当日单号最大序号：WD + yyyyMMdd + - + 6 位，序号从第 12 位起（与 WO/AP 同构）。 */
    @Query(value = "select coalesce(max(cast(substring(wd_no from 12) as bigint)), 0) " +
            "from writeoff_desk_task where wd_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
