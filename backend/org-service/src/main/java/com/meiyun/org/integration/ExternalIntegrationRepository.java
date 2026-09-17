package com.meiyun.org.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 外部依赖配置窗口仓储（V34）。目录由迁移幂等播种，代码侧不做自由新建/删除。
 */
public interface ExternalIntegrationRepository extends JpaRepository<ExternalIntegration, Long> {

    Optional<ExternalIntegration> findByIntegrationCode(String integrationCode);

    List<ExternalIntegration> findAllByOrderByCategoryAscIdAsc();

    List<ExternalIntegration> findByEnabledTrue();
}
