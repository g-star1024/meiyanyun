package com.meiyun.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface ComplianceCheckRepository
        extends JpaRepository<ComplianceCheck, Long>, JpaSpecificationExecutor<ComplianceCheck> {

    @Query("SELECT c.storeName, c.category, COUNT(c), " +
           "SUM(CASE WHEN c.status = 'PASS' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN c.status = 'WARN' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN c.status = 'FAIL' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN c.status = 'PENDING' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN c.status = 'PASS' AND c.updatedAt > c.createdAt THEN 1 ELSE 0 END) " +
           "FROM ComplianceCheck c " +
           "WHERE c.lastCheckAt >= :from AND c.lastCheckAt < :to " +
           "GROUP BY c.storeName, c.category")
    List<Object[]> complianceStats(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
