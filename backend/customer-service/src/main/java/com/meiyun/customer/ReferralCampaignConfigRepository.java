package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

/** 邀请机制全局配置（P5-B89，单行 GLOBAL）。 */
public interface ReferralCampaignConfigRepository extends JpaRepository<ReferralCampaignConfig, String> {
}
