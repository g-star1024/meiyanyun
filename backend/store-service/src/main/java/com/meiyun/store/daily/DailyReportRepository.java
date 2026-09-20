package com.meiyun.store.daily;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long>,
        JpaSpecificationExecutor<DailyReport> {

    Optional<DailyReport> findByStoreCodeAndDate(String storeCode, LocalDate date);
}
