package com.meiyun.org.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationOutboxRepository extends JpaRepository<IntegrationOutbox, Long> {

    Optional<IntegrationOutbox> findByConnectorIdAndTxnNo(Long connectorId, String txnNo);

    boolean existsByConnectorIdAndTxnNo(Long connectorId, String txnNo);

    List<IntegrationOutbox> findAllByOrderByOccurredAtDesc(Pageable pageable);

    List<IntegrationOutbox> findByStatusOrderByOccurredAtDesc(String status, Pageable pageable);

    List<IntegrationOutbox> findByConnectorIdOrderByOccurredAtDesc(Long connectorId, Pageable pageable);

    List<IntegrationOutbox> findByConnectorIdAndStatusOrderByOccurredAtDesc(Long connectorId, String status, Pageable pageable);

    /** 当日消息号最大序号（outbox_no 形如 OB20260928-000001，序号从第 12 位起 6 位，仿 ContractRepository 先例）。 */
    @Query(value = "select coalesce(max(cast(substring(outbox_no from 12) as bigint)), 0) "
            + "from integration_outbox where outbox_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);

    /** 对账窗口：单连接器 occurred_at 落 [from, to) 的全量行。 */
    List<IntegrationOutbox> findByConnectorIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long connectorId, OffsetDateTime from, OffsetDateTime to);

    /** 对账窗口：全部连接器汇总（scope=0）occurred_at 落 [from, to) 的全量行。 */
    List<IntegrationOutbox> findByOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            OffsetDateTime from, OffsetDateTime to);
}
