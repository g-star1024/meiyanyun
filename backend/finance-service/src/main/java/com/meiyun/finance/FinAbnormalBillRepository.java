package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FinAbnormalBillRepository
        extends JpaRepository<FinAbnormalBill, String>,
                JpaSpecificationExecutor<FinAbnormalBill> {

    /**
     * 当日最大序号：bill_no 形如 AB20260919-000001，前 11 位为 AB+yyyyMMdd+'-'，
     * 序号自第 12 位起 6 位（同事务内未 flush 的行对本查询不可见，序号须在事务外/循环外取一次）。
     */
    @Query(value = "select coalesce(max(cast(substring(bill_no from 12) as integer)), 0) "
            + "from fin_abnormal_bill where bill_no like :prefix", nativeQuery = true)
    int maxSeqOfDay(@Param("prefix") String prefix);

    Optional<FinAbnormalBill> findByApprovalNo(String approvalNo);

    Optional<FinAbnormalBill> findByIdemKey(String idemKey);
}
