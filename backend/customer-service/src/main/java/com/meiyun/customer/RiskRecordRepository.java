package com.meiyun.customer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 风控记录仓库（M3-B6，表 risk_record）。 */
public interface RiskRecordRepository extends JpaRepository<RiskRecord, Long> {

    /** 当日风控单号最大者（RK+yyyyMMdd- 前缀 LIKE，单号生成当日 max+1）。 */
    Optional<RiskRecord> findTopByRiskNoLikeOrderByRiskNoDesc(String likePattern);

    /** 列表全量（命中次数降序，照 mock 活规格 filtered 排序口径）。 */
    List<RiskRecord> findAllByOrderByHitCountDesc();
}
