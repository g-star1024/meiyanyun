package com.meiyun.customer;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReferralRepository extends JpaRepository<Referral, String>, JpaSpecificationExecutor<Referral> {

    /** 下单幂等：同幂等键重放返回既有单（网络重试/重复点击不重复落库）。 */
    Optional<Referral> findFirstByClientToken(String clientToken);

    /**
     * 当日单号最大序号（referral_id 形如 RF20260923-000001：2 位前缀 + 8 位日期 + 连字符在第 11 位，序号从第 12 位起 6 位）。
     * 库内当日最大号递增，synchronized 防并发重号（铁律 6）。
     */
    @Query(value = "select coalesce(max(cast(substring(referral_id from 12) as bigint)), 0) "
            + "from referral where referral_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);

    long countByStatus(String status);

    /** 成交总额（分）：KPI 读模型。 */
    @Query("select coalesce(sum(r.dealAmountCents), 0) from Referral r where r.status = 'DEAL'")
    long sumDealAmountCents();

    /** 被推荐人活跃绑定查重（与 V44 部分唯一索引 uk_referral_referee_active 同口径：代码友好报错 + 索引兜底）。 */
    boolean existsByRefereeCustomerIdAndStatusIn(String refereeCustomerId, List<String> statuses);

    /** 卡3 列表富化：按推荐人批量统计累计推荐数（group by 一次查询，防 N+1）。 */
    @Query("select r.referrerCustomerId, count(r) from Referral r where r.referrerCustomerId in :ids group by r.referrerCustomerId")
    List<Object[]> countGroupByReferrer(@Param("ids") Collection<String> ids);

    /** 到期扫描（ReferralExpireJob：expire_at 已过且状态仍活跃的单，按到期时间升序批处理）。 */
    List<Referral> findByStatusInAndExpireAtBeforeOrderByExpireAtAsc(List<String> statuses, OffsetDateTime now, Pageable pageable);
}
