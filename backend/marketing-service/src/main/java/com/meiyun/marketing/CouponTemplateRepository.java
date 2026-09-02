package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponTemplateRepository extends JpaRepository<CouponTemplate, String> {
    List<CouponTemplate> findAllByOrderByCreatedAtDesc();
}
