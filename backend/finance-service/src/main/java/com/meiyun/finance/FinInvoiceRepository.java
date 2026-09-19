package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface FinInvoiceRepository
        extends JpaRepository<FinInvoice, Long>,
        JpaSpecificationExecutor<FinInvoice> {

    Optional<FinInvoice> findByIdemKey(String idemKey);

    /**
     * 票号当日序号用：查当日最大序号。
     * invoice_no 形如 INV-20260905-0001（INV- 4 位 + yyyyMMdd 8 位 + '-' 1 位 = 13 位前缀，
     * 序号自第 14 位起 4 位数字）。
     */
    @org.springframework.data.jpa.repository.Query(
            value = "select coalesce(max(cast(substring(invoice_no from 14) as integer)), 0) " +
                    "from fin_invoice where invoice_no like :prefix", nativeQuery = true)
    int maxSeqOfDay(@org.springframework.data.repository.query.Param("prefix") String prefix);

    /**
     * 申报快照销项税额（分）：开具时间落在 [from, to) 且当期状态为 ISSUED 的销项票。
     * 已红冲（RED_FLUSHED）与作废（VOIDED）票不计当期销项。
     */
    @Query("select coalesce(sum(i.taxAmount), 0) from FinInvoice i "
            + "where i.issuedAt >= :from and i.issuedAt < :to and i.status = 'ISSUED'")
    long sumIssuedTaxBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
