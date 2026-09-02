package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CouponGrantRepository extends JpaRepository<CouponGrant, String> {
    List<CouponGrant> findAllByOrderByGrantedAtDesc();

    /** 单据号生成：取当日同前缀最大号（参数如 GR20260902-%）。 */
    Optional<CouponGrant> findTopByGrantIdLikeOrderByGrantIdDesc(String prefix);
}
