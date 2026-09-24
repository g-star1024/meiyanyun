package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 转介绍活动（P5-B89）。 */
public interface ReferralCampaignRepository
        extends JpaRepository<ReferralCampaign, String>, JpaSpecificationExecutor<ReferralCampaign> {

    /** P5-B91 D10：当日活动单号最大序号（campaign_id 形如 RC20260924-000001：序号从第 12 位起 6 位）。 */
    @Query(value = "select coalesce(max(cast(substring(campaign_id from 12) as bigint)), 0) "
            + "from referral_campaign where campaign_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
