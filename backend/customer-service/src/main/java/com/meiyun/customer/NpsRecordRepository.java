package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** NPS 回访记录仓库（M3-B1 / M3-12）。 */
public interface NpsRecordRepository extends JpaRepository<NpsRecord, Long> {

    Optional<NpsRecord> findByRecordNo(String recordNo);

    /** 提交幂等先查：(customer_id, period) 已存在则直接返回 dedup（V54 部分唯一索引兜底并发）。 */
    Optional<NpsRecord> findByCustomerIdAndPeriod(String customerId, String period);

    /** 列表数据源：按提交时刻倒序（M3-12 列表行）。 */
    List<NpsRecord> findAllByOrderByCreatedAtDesc();

    /** 库内 NR#### 最大编号（定长 4 位序号，字符串 max 与数值序一致；照 TA%03d 先例）。 */
    @Query("select max(n.recordNo) from NpsRecord n where n.recordNo like 'NR%'")
    String maxRecordNo();

    /** 趋势聚合：按 ISO 周分桶（period 字典序与时间序一致），startPeriod 起各桶分类计数。 */
    @Query(value = "SELECT period, COUNT(*) AS total, "
            + "SUM(CASE WHEN category = 'PROMOTER' THEN 1 ELSE 0 END) AS promoters, "
            + "SUM(CASE WHEN category = 'PASSIVE' THEN 1 ELSE 0 END) AS passives, "
            + "SUM(CASE WHEN category = 'DETRACTOR' THEN 1 ELSE 0 END) AS detractors "
            + "FROM nps_record WHERE period >= :startPeriod GROUP BY period ORDER BY period",
            nativeQuery = true)
    List<Object[]> trendSince(@Param("startPeriod") String startPeriod);
}
