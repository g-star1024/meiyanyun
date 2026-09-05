package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinChangeLogRepository extends JpaRepository<FinChangeLog, Long> {

    List<FinChangeLog> findTop50ByOrderByLogAtDesc();
}
