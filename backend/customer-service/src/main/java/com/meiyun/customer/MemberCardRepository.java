package com.meiyun.customer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberCardRepository extends JpaRepository<MemberCard, String> {
    List<MemberCard> findByCustomerId(String customerId);
    List<MemberCard> findByCustomerIdAndStatus(String customerId, String status);

    /** 财务卡余额聚合：按门店列卡（卡号倒序）；无门店过滤时用 findAll。 */
    List<MemberCard> findByStoreCodeOrderByCardNoDesc(String storeCode);

    /**
     * 余额变动（充值/卡扣/退卡）同事务行锁找卡：SELECT ... FOR UPDATE，
     * 并发充值/扣款串行化，防余额更新丢失。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MemberCard c where c.cardNo = :cardNo")
    Optional<MemberCard> findForUpdate(@Param("cardNo") String cardNo);

    /** 当日售卡开卡卡号最大序号（card_no 形如 MC20260907-000001，序号从第 12 位起 6 位；与 RC/OD/PM 各单号同口径）。 */
    @Query(value = "select coalesce(max(cast(substring(card_no from 12) as bigint)), 0) "
            + "from member_card where card_no like :prefix", nativeQuery = true)
    long maxCardSeqOfDay(@Param("prefix") String prefix);

    /** 售卡开卡幂等反查：按售卡订单号（sale_no）查已开出的卡，收款回调重试重放不重复开卡。 */
    Optional<MemberCard> findFirstBySaleNo(String saleNo);

    /**
     * B83 卡1 L66 疗程跟踪聚合读：按门店列 COURSE 卡（JOIN customer 取姓名/手机号）。
     * 只投影读模型所需列，trackStatus/daysLeft/usedTimes/phoneMask 在 Controller 侧 Java 推导。
     * 与前端 mock store asset.ts 的 TimesAsset 结构对齐，全量返回（约 86 条在用卡，不分页）。
     */
    @Query(value = """
            select mc.card_no as cardNo,
                   mc.customer_id as customerId,
                   c.name as customerName,
                   c.phone as phone,
                   mc.card_item as cardItem,
                   mc.total_times as totalTimes,
                   mc.remain_times as remainTimes,
                   mc.balance as balance,
                   mc.status as status,
                   mc.expires_at as expiresAt
            from member_card mc
            join customer c on mc.customer_id = c.customer_id
            where mc.card_type = 'COURSE'
              and mc.store_code = :storeCode
            order by mc.expires_at asc nulls last, mc.card_no asc
            """, nativeQuery = true)
    List<CourseTrackRow> findCourseTrackRows(@Param("storeCode") String storeCode);
}
