package com.meiyun.store.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface HealthIssueRepository extends JpaRepository<HealthIssue, Long> {

    List<HealthIssue> findAllByOrderByIdAsc();

    List<HealthIssue> findByStoreCodeOrderByIdAsc(String storeCode);

    List<HealthIssue> findByStatusOrderByIdAsc(String status);

    List<HealthIssue> findByStoreCodeAndStatusOrderByIdAsc(String storeCode, String status);

    long countByStoreCodeAndSeverityAndStatusIn(String storeCode, String severity, Collection<String> statuses);
}
