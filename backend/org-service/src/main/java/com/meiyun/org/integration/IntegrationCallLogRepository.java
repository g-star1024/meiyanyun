package com.meiyun.org.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationCallLogRepository extends JpaRepository<IntegrationCallLog, Long> {

    List<IntegrationCallLog> findByConnectorIdOrderByRequestAtDesc(Long connectorId, Pageable pageable);

    List<IntegrationCallLog> findAllByOrderByRequestAtDesc(Pageable pageable);

    /** 幂等键定位（T3-B2 retry 复用 transaction_id=txn_no 更新既有日志行，uk 锚不重复落行）。 */
    Optional<IntegrationCallLog> findByConnectorIdAndTransactionId(Long connectorId, String transactionId);

    /** 24h 调用/失败聚合计数（连接器目录视图用真实计数，不用种子假数）。 */
    @Query("SELECT l.connectorId, COUNT(l), SUM(CASE WHEN l.status = 'FAIL' THEN 1 ELSE 0 END) "
            + "FROM IntegrationCallLog l WHERE l.requestAt >= :since GROUP BY l.connectorId")
    List<Object[]> count24hByConnector(@Param("since") OffsetDateTime since);
}
