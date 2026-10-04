package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataGovernRuleRepository extends JpaRepository<DataGovernRule, Long> {

    /** 规则列表按 id 升序（种子插入序=前端 mock 数组序，id 自增天然保序）。 */
    List<DataGovernRule> findAllByOrderByIdAsc();

    /** 启用规则按 id 升序（GovernScanJob 定时扫描源）。 */
    List<DataGovernRule> findByEnabledTrueOrderByIdAsc();
}
