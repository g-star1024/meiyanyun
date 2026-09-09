package com.meiyun.marketing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** 过期扫描（限批 Pageable，防全表一事务毒化；调用方逐条独立事务处理）。 */
    List<CustomerGrant> findByStatusAndExpireAtBefore(String status, OffsetDateTime before, Pageable pageable);

    long countByStatus(String status);

    /** 全量客户赠金余额汇总（report 用）。 */
    @Query("select g.customerId, coalesce(sum(g.balanceFen),0) from CustomerGrant g " +
            "where g.status = 'VALID' group by g.customerId")
    List<Object[]> sumBalanceGroupByCustomer();
}
