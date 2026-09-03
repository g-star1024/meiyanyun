package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CouponWriteoffRecordRepository extends JpaRepository<CouponWriteoffRecord, String> {

    List<CouponWriteoffRecord> findAllByOrderByVerifiedAtDesc();

    /** 单据号生成：取当日同前缀最大号（参数如 WR20260903-%）。 */
    Optional<CouponWriteoffRecord> findTopByWriteoffIdLikeOrderByWriteoffIdDesc(String prefix);

    /** 防重复核销：同一券码是否已有 OK 流水。 */
    boolean existsByCouponCodeIgnoreCaseAndStatus(String couponCode, String status);
}
