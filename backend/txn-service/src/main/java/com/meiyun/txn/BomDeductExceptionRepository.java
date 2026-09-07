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

    /** 当日异常单号最大序号（exc_id 形如 BEX20260906-000003，序号从第 12 位起 6 位）。 */
    @Query(value = "select coalesce(max(cast(substring(exc_id from 12) as bigint)), 0) "
            + "from bom_deduct_exception where exc_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
