package com.meiyun.org.integration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationConnectorRepository extends JpaRepository<IntegrationConnector, Long> {

    Optional<IntegrationConnector> findByCode(String code);

    boolean existsByCode(String code);
}
