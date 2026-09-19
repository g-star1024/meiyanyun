package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface FinInputInvoiceRepository
        extends JpaRepository<FinInputInvoice, Long>,
                JpaSpecificationExecutor<FinInputInvoice> {

    Optional<FinInputInvoice> findByIdemKey(String idemKey);

    Optional<FinInputInvoice> findByRegisterNo(String registerNo);

    /**
     * 当日最大序号：register_no 形如 PINV-yyyyMMdd-0001，前缀 14 位（PINV-yyyyMMdd-），
     * 序号自第 15 位起 4 位。同事务内未 flush 的行对本查询不可见，序号须在事务外取一次。
     */
    @Query(value = "select coalesce(max(cast(substring(register_no from 15) as integer)), 0) "
            + "from fin_input_invoice where register_no like :prefix", nativeQuery = true)
    int maxSeqOfDay(@Param("prefix") String prefix);

    /** 归属某申报期、指定状态集合的税额合计（分）。 */
    @Query("select coalesce(sum(i.taxAmount), 0) from FinInputInvoice i "
            + "where i.periodId = :periodId and i.status in :statuses")
    long sumTaxByPeriodAndStatuses(@Param("periodId") Long periodId,
                                   @Param("statuses") Collection<String> statuses);

    /** 归属某申报期的进项转出额合计（分，仅 TRANSFERRED_OUT 票）。 */
    @Query("select coalesce(sum(i.transferOutAmount), 0) from FinInputInvoice i "
            + "where i.periodId = :periodId and i.status = 'TRANSFERRED_OUT'")
    long sumTransferOutByPeriod(@Param("periodId") Long periodId);

    /** 归属某申报期、非不抵扣终态票的价税合计（分）。 */
    @Query("select coalesce(sum(i.amount), 0) from FinInputInvoice i "
            + "where i.periodId = :periodId and i.status <> 'NON_DEDUCTIBLE'")
    long sumAmountByPeriod(@Param("periodId") Long periodId);

    /** 全局已用途确认（purpose=DEDUCT）尚未抵扣的税额合计（分），供「待抵扣」展示。 */
    @Query("select coalesce(sum(i.taxAmount), 0) from FinInputInvoice i "
            + "where i.status = 'CONFIRMED' and i.purpose = 'DEDUCT'")
    long sumConfirmedDeductTax();

    /** 重复入账防控：同销方税号＋发票号码在活跃状态集合中是否已存在（部分唯一索引 uk_fin_input_invoice_dedup 的服务层预检）。 */
    boolean existsBySellerTaxNoAndInvoiceNoAndStatusIn(String sellerTaxNo, String invoiceNo,
                                                       Collection<String> statuses);
}
