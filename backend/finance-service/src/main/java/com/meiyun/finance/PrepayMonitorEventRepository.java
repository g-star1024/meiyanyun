package com.meiyun.finance;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrepayMonitorEventRepository extends JpaRepository<PrepayMonitorEvent, Long> {

    /** 同规则同店当前 OPEN 事件（部分唯一索引 uk_..._open 保证至多一条）。 */
    Optional<PrepayMonitorEvent> findFirstByRuleCodeAndStoreCodeAndStatus(
            String ruleCode, String storeCode, String status);

    /** 某规则某店历史事件数，用于下一轮 OPEN 的 R{轮次} 幂等键。 */
    @Query("select count(e) from PrepayMonitorEvent e where e.ruleCode = :ruleCode and e.storeCode = :storeCode")
    long countByRuleAndStore(@Param("ruleCode") String ruleCode, @Param("storeCode") String storeCode);

    /** 某规则当前全部 OPEN 事件（扫描回转：本轮不再命中的门店批量置 RESOLVED）。 */
    List<PrepayMonitorEvent> findByRuleCodeAndStatus(String ruleCode, String status);

    List<PrepayMonitorEvent> findByStatus(String status, Sort sort);

    List<PrepayMonitorEvent> findByStatusAndStoreCodeIn(String status, List<String> storeCodes, Sort sort);
}
