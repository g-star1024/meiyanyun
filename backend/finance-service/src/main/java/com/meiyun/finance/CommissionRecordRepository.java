package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CommissionRecordRepository extends JpaRepository<CommissionRecord, String> {

    /** 月度 per 人幂等键：同月同人唯一。 */
    Optional<CommissionRecord> findByPeriodAndStaffId(LocalDate period, String staffId);

    List<CommissionRecord> findByPeriodOrderByCommissionDesc(LocalDate period);

    List<CommissionRecord> findByPeriodAndStoreCodeOrderByCommissionDesc(LocalDate period, String storeCode);

    /** 月结结转用：当月已审批（APPROVED/PAID）提成合计（分）。 */
    @Query("select coalesce(sum(c.commission),0) from CommissionRecord c " +
           "where c.period = :period and c.status in ('APPROVED','PAID')")
    long sumApprovedCommission(@Param("period") LocalDate period);
}
