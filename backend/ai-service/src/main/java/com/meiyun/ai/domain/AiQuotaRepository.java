package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiQuotaRepository extends JpaRepository<AiQuota, Long> {

    Optional<AiQuota> findByQuotaScopeAndTargetCode(String quotaScope, String targetCode);

    boolean existsByQuotaScopeAndTargetCode(String quotaScope, String targetCode);

    List<AiQuota> findAllByOrderByQuotaScopeAscTargetCodeAsc();
}
