package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
