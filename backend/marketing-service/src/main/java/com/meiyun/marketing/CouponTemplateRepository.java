package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CouponTemplateRepository extends JpaRepository<CouponTemplate, String> {
    List<CouponTemplate> findAllByOrderByCreatedAtDesc();

    /** 单据号生成：取当日同前缀最大号（参数如 CPN20260902-%）。 */
    Optional<CouponTemplate> findTopByCouponIdLikeOrderByCouponIdDesc(String prefix);
}
