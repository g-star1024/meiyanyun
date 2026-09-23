package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** 转介绍活动（P5-B89）。 */
public interface ReferralCampaignRepository
        extends JpaRepository<ReferralCampaign, String>, JpaSpecificationExecutor<ReferralCampaign> {
}
