package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, String> {
    List<Campaign> findAllByOrderByCreatedAtDesc();

    /** 单据号生成：取当日同前缀最大号（参数如 CP20260902-%）。 */
    Optional<Campaign> findTopByCampaignIdLikeOrderByCampaignIdDesc(String prefix);
}
