package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MktDealAttributionRepository extends JpaRepository<MktDealAttribution, Long> {

    /** 幂等锚查找：paid 段去重、refund 段命中判定共用。 */
    Optional<MktDealAttribution> findByOrderNo(String orderNo);
}
