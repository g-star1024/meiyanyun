package com.meiyun.store.weekly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, Long>,
        JpaSpecificationExecutor<WeeklyReport> {
}
