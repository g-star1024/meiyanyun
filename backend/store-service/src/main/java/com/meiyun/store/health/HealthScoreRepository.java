package com.meiyun.store.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface HealthScoreRepository extends JpaRepository<HealthScore, Long> {

    List<HealthScore> findByStoreCode(String storeCode);

    List<HealthScore> findByStoreCodeIn(Collection<String> storeCodes);
}
