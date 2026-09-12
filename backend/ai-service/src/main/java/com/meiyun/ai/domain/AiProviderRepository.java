package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {
    Optional<AiProvider> findByProviderCode(String providerCode);
    boolean existsByProviderCode(String providerCode);
}
