package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MallExchangeRepository extends JpaRepository<MallExchange, String> {

    List<MallExchange> findAllByOrderByCreatedAtDesc();

    List<MallExchange> findByStatusOrderByCreatedAtDesc(String status);

    List<MallExchange> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    /** 下单幂等：同客户同幂等键重放返回既有兑换单（网络重试/重复点击不重复落库）。 */
    Optional<MallExchange> findFirstByClientToken(String clientToken);

    /**
     * 当日兑换单号最大序号（exchange_id 形如 EX20260908-000001：2 位前缀 + 8 位日期 + 连字符在第 11 位，序号从第 12 位起 6 位）。
     * 库内当日最大号递增，synchronized 防并发重号（铁律 6）。
     */
    @Query(value = "select coalesce(max(cast(substring(exchange_id from 12) as bigint)), 0) "
            + "from mall_exchange where exchange_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
