package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MallProductRepository extends JpaRepository<MallProduct, String> {

    List<MallProduct> findAllByOrderByCreatedAtDesc();

    List<MallProduct> findByStatusOrderByPointsPriceAsc(String status);

    /**
     * 当日商品单号最大序号（product_id 形如 MP20260908-000001：2 位前缀 + 8 位日期 + 连字符在第 11 位，序号从第 12 位起 6 位）。
     * 与 RC/MC 单号同口径：库内当日最大号递增，synchronized 防并发重号（铁律 6，禁内存 AtomicLong）。
     */
    @Query(value = "select coalesce(max(cast(substring(product_id from 12) as bigint)), 0) "
            + "from mall_product where product_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
