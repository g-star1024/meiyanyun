package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CouponWriteoffChainRepository extends JpaRepository<CouponWriteoffChain, String> {
    List<CouponWriteoffChain> findAllByOrderByChainIdAsc();
}
