package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BizTargetRepository extends JpaRepository<BizTarget, Long> {

    Optional<BizTarget> findByTargetId(String targetId);

    Optional<BizTarget> findByIdemKey(String idemKey);

    List<BizTarget> findAllByOrderByIdAsc();

    List<BizTarget> findByOwnerTypeOrderByIdAsc(String ownerType);

    List<BizTarget> findByMetricOrderByIdAsc(String metric);

    List<BizTarget> findByPeriodOrderByIdAsc(String period);

    List<BizTarget> findByApprovalOrderByIdAsc(String approval);
}
