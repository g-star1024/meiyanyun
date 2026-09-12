package com.meiyun.marketing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerGrantRepository extends JpaRepository<CustomerGrant, Long> {

    List<CustomerGrant> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    /** 按非空幂等键查重（手工/规则统一入口）。 */
    Optional<CustomerGrant> findByIdemKey(String idemKey);

    /**
     * 订单级来源号查重（B37 卡2 消费满额自动发赠金）。
     *
     * <p>{@code idem_key=RULE:{ruleId}:{orderNo}} 只防「同规则同订单」重复；运营若在两笔收款之间
     * 停用规则 A、启用同门槛规则 B，单靠 idem_key 会让同一订单被 B 再发一次。source_biz_ref=orderNo
     * 锚定「一笔已收款订单至多被自动规则发放一次」，跨规则改动同样吞掉（手工发放 sourceBizRef 为
     * MANUAL:{cid}:{号}，不会与订单号冲突）。
     */
    boolean existsBySourceBizRef(String sourceBizRef);

    @Query("select coalesce(sum(g.balanceFen),0) from CustomerGrant g " +
            "where g.customerId = :cid and g.status = 'VALID'")
    Long sumBalanceByCustomer(@Param("cid") String cid);

    /**
     * 收银台抵扣取券：同事务行锁按「先到期先用」FIFO 列出客户可用赠金，
     * SELECT ... FOR UPDATE 串行化并发扣减，防余额更新丢失导致的赠金双花。
     *
     * <p>只取 VALID 且未过期者：过期扫描是定时任务，可能尚未跑到，抵扣侧必须自行按 now 兜底，
     * 否则会扣到「事实已过期但状态仍 VALID」的赠金。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CustomerGrant g where g.customerId = :cid and g.status = 'VALID' " +
            "and g.expireAt > :now and g.balanceFen > 0 order by g.expireAt asc, g.id asc")
    List<CustomerGrant> findUsableForUpdate(@Param("cid") String cid, @Param("now") OffsetDateTime now);

    /**
     * B39 退款回加取券：按 id 集合对涉及的赠金券行加行锁（含 USED/EXPIRED 状态，
     * 与 {@link #findUsableForUpdate} 只锁可用券不同——回加目标恰是被扣尽的 USED 券），
     * 串行化并发退款对同一券行的余额回补，防更新丢失。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CustomerGrant g where g.id in :ids order by g.id asc")
    List<CustomerGrant> findByIdInForUpdate(@Param("ids") List<Long> ids);

    /** 过期扫描（限批 Pageable，防全表一事务毒化；调用方逐条独立事务处理）。 */
    List<CustomerGrant> findByStatusAndExpireAtBefore(String status, OffsetDateTime before, Pageable pageable);

    long countByStatus(String status);

    /** 全量客户赠金余额汇总（report 用）。 */
    @Query("select g.customerId, coalesce(sum(g.balanceFen),0) from CustomerGrant g " +
            "where g.status = 'VALID' group by g.customerId")
    List<Object[]> sumBalanceGroupByCustomer();
}
