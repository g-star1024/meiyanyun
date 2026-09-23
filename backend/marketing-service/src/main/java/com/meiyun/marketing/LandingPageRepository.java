package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LandingPageRepository extends JpaRepository<LandingPage, String> {
    List<LandingPage> findAllByOrderByCreatedAtDesc();

    /** 单据号生成：取当日同前缀最大号（参数如 LP20260923-%）。 */
    Optional<LandingPage> findTopByPageIdLikeOrderByPageIdDesc(String prefix);

    /** 创建幂等：client_token 命中即返回已有行。 */
    Optional<LandingPage> findByClientToken(String clientToken);
}
