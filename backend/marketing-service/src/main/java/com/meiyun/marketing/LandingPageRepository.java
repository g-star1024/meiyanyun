package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LandingPageRepository extends JpaRepository<LandingPage, String> {
    List<LandingPage> findAllByOrderByCreatedAtDesc();

    /** 单据号生成：取当日同前缀最大号（参数如 LP20260923-%）。 */
    Optional<LandingPage> findTopByPageIdLikeOrderByPageIdDesc(String prefix);

    /** 创建幂等：client_token 命中即返回已有行。 */
    Optional<LandingPage> findByClientToken(String clientToken);

    /** 访问量原子自增（P5-B98 采集联动：与 touch_event 落库同事务，UPDATE 直改防 lost update）。 */
    @Modifying
    @Query("UPDATE LandingPage p SET p.visits = p.visits + 1 WHERE p.pageId = :pageId")
    int bumpVisits(@Param("pageId") String pageId);

    /** 留资量原子自增（P5-B98 采集联动：与 touch_event 落库同事务，UPDATE 直改防 lost update）。 */
    @Modifying
    @Query("UPDATE LandingPage p SET p.leads = p.leads + 1 WHERE p.pageId = :pageId")
    int bumpLeads(@Param("pageId") String pageId);
}
