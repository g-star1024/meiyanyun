package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarketingWeeklySubRepository extends JpaRepository<MarketingWeeklySub, Long> {

    /** 本人订阅行（一人一行，staff_no UK）。 */
    Optional<MarketingWeeklySub> findByStaffNo(String staffNo);

    /** 全部启用订阅（周报 Job 扫描）。 */
    List<MarketingWeeklySub> findByEnabledTrue();
}
