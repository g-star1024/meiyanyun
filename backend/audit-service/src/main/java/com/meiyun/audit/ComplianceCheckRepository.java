package com.meiyun.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface ComplianceCheckRepository
        extends JpaRepository<ComplianceCheck, Long>, JpaSpecificationExecutor<ComplianceCheck> {
}
