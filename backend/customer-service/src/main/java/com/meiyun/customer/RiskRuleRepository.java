package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 风控规则仓库（M3-B6，表 risk_rule）。 */
public interface RiskRuleRepository extends JpaRepository<RiskRule, Long> {

    /** 规则卡格全量（按 id 升序＝种子 RR-1..RR-5 序）。 */
    List<RiskRule> findAllByOrderByIdAsc();
}
