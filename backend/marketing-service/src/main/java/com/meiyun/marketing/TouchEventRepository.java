package com.meiyun.marketing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TouchEventRepository extends JpaRepository<TouchEvent, Long> {

    /** 采集幂等查重：(client_token, touch_type) 命中即重放（idemKey 范式，D8）。 */
    Optional<TouchEvent> findByClientTokenAndTouchType(String clientToken, String touchType);

    /** T2-01 触点时间线：按发生时刻倒序分页。 */
    List<TouchEvent> findAllByOrderByAtDesc(Pageable pageable);

    /** T2-01 五通道聚合：touch_type 分组计数＋最近触点时刻＋今日触点（todayStart 由调用方按业务日界传入）。 */
    @Query("SELECT t.touchType AS touchType, COUNT(t) AS cnt, MAX(t.at) AS latestAt, "
            + "SUM(CASE WHEN t.at >= :todayStart THEN 1 ELSE 0 END) AS todayCnt "
            + "FROM TouchEvent t GROUP BY t.touchType")
    List<TouchTypeSummary> summarizeByType(@Param("todayStart") OffsetDateTime todayStart);

    /** B99 转化漏斗线索级：区间 LANDING_LEAD 留资计数（半闭 [from,to)），供 finance 聚合层合并 lead。 */
    @Query("SELECT COUNT(t) FROM TouchEvent t WHERE t.touchType = 'LANDING_LEAD' "
            + "AND t.at >= :from AND t.at < :to")
    long countLeads(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 分组聚合投影（JPQL 接口投影）。 */
    interface TouchTypeSummary {
        String getTouchType();

        long getCnt();

        OffsetDateTime getLatestAt();

        long getTodayCnt();
    }
}
