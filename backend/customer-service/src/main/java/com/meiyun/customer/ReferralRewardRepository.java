package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReferralRewardRepository extends JpaRepository<ReferralReward, String> {

    List<ReferralReward> findByReferralIdOrderByCreatedAtDesc(String referralId);

    /** 卡3 列表富化：批量取本页全部转介绍单的奖励（按创建时间倒序，内存分组取首条，防 N+1）。 */
    List<ReferralReward> findByReferralIdInOrderByCreatedAtDesc(Collection<String> ids);

    /** 奖励幂等：idem_key = referralId:triggerEvent:rewardType，重复登记返回既有记录（不重复落库）。 */
    Optional<ReferralReward> findFirstByIdemKey(String idemKey);

    /** 当日奖励单号最大序号（reward_id 形如 RW20260923-000001：序号从第 12 位起 6 位）。 */
    @Query(value = "select coalesce(max(cast(substring(reward_id from 12) as bigint)), 0) "
            + "from referral_reward where reward_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
