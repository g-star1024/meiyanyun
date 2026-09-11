package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** BOM 扣料异常仓库 */
public interface BomDeductExceptionRepository
        extends JpaRepository<BomDeductException, String>, JpaSpecificationExecutor<BomDeductException> {

    /** 一个划扣单最多一条异常（upsert 定位） */
    Optional<BomDeductException> findByWriteoffId(String writeoffId);

    /**
     * 当日异常单号最大序号（exc_id 形如 BEX20260906-000003）。
     *
     * <p>BEX 为 <b>3 字母</b>前缀，连字符落在第 12 位，序号须从第 <b>13</b> 位起 6 位——
     * 不同于本仓库多数 2 字母前缀单号（WO/AP/RF…）的 {@code from 12}。此前误用 12 会取到
     * {@code "-000001"}，cast 成 -1 导致序号回退并与既有单号撞键（JPA 转 UPDATE，
     * created_at 空值触发非空约束）。
     */
    @Query(value = "select coalesce(max(cast(substring(exc_id from 13) as bigint)), 0) "
            + "from bom_deduct_exception where exc_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
