package com.meiyun.org.integration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationReconcileBatchRepository extends JpaRepository<IntegrationReconcileBatch, Long> {

    /** 幂等锚：uk(connector_id, biz_date)，重放返既有批次。 */
    Optional<IntegrationReconcileBatch> findByConnectorIdAndBizDate(Long connectorId, LocalDate bizDate);

    List<IntegrationReconcileBatch> findAllByOrderByStartedAtDesc(Pageable pageable);

    /** 当日批次号最大序号（batch_no 形如 REC20260928-000001，序号从第 13 位起 6 位，仿 ContractRepository 先例）。 */
    @Query(value = "select coalesce(max(cast(substring(batch_no from 13) as bigint)), 0) "
            + "from integration_reconcile_batch where batch_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
