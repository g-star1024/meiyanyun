package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface PushRecordRepository extends JpaRepository<PushRecord, Long> {

    /** 统计某客户在时间窗口内的触达条数（周频限制用）。 */
    @Query("select count(p) from PushRecord p where p.customerId = :cid and p.sentAt >= :since")
    long countByCustomerSince(@Param("cid") String customerId, @Param("since") OffsetDateTime since);

    List<PushRecord> findByCustomerIdOrderBySentAtDesc(String customerId);
}
