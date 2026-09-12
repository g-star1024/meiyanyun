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

    /** 过期扫描（限批 Pageable，防全表一事务毒化；调用方逐条独立事务处理）。 */
    List<CustomerGrant> findByStatusAndExpireAtBefore(String status, OffsetDateTime before, Pageable pageable);

    long countByStatus(String status);

    /** 全量客户赠金余额汇总（report 用）。 */
    @Query("select g.customerId, coalesce(sum(g.balanceFen),0) from CustomerGrant g " +
            "where g.status = 'VALID' group by g.customerId")
    List<Object[]> sumBalanceGroupByCustomer();
}
