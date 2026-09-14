package com.meiyun.store.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, String> {

    List<HealthCheck> findAllByOrderByStoreCodeAsc();
}
