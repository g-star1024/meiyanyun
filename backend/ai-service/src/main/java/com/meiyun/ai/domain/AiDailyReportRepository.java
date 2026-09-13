package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiDailyReportRepository extends JpaRepository<AiDailyReport, Long> {

    /** 指定日期+门店的全部版本（同键重生成多行），按版本倒序，首行为最新版。 */
    List<AiDailyReport> findByReportDateAndStoreCodeOrderByReportIdDesc(String reportDate, String storeCode);

    /** 历史日报：取每个日期+门店的最新版由 Service 归并；先按创建倒序取近一批，读侧裁剪。 */
    List<AiDailyReport> findTop60ByOrderByReportIdDesc();

    Optional<AiDailyReport> findFirstByOrderByReportIdDesc();

    long countByReportDate(String reportDate);
}
