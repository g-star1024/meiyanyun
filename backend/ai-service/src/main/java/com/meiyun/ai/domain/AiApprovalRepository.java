package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface AiApprovalRepository extends JpaRepository<AiApproval, Long> {
    Page<AiApproval> findByStatusOrderByApprovalIdDesc(String status, Pageable pageable);
    Page<AiApproval> findAllByOrderByApprovalIdDesc(Pageable pageable);
    long countByStatus(String status);
    long countByStatusAndDecidedAtGreaterThanEqual(String status, OffsetDateTime since);

    boolean existsByApprovalTypeAndTargetIdAndStatus(String approvalType, Long targetId, String status);
}
