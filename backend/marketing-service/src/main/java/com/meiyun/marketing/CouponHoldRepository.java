package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CouponHoldRepository extends JpaRepository<CouponHold, Long> {

    /** 按非空幂等键查重（C 端领取幂等重放直返既有行）。 */
    Optional<CouponHold> findByIdemKey(String idemKey);

    List<CouponHold> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    long countByCustomerIdAndStatus(String customerId, String status);
}
