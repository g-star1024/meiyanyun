package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiChurnPredictionRepository extends JpaRepository<AiChurnPrediction, Long> {

    /** 某批次风险榜：按流失风险分倒序。 */
    List<AiChurnPrediction> findByBatchNoOrderByScoreDescPredictionIdAsc(String batchNo);

    /** 某批次 + 风险等级筛选榜单。 */
    List<AiChurnPrediction> findByBatchNoAndRiskLevelOrderByScoreDescPredictionIdAsc(
            String batchNo, String riskLevel);

    long countByBatchNo(String batchNo);

    /** 当日批次计数（前缀 CH+yyyyMMdd），用于批次号自增序号。 */
    long countByBatchNoStartingWith(String prefix);

    /** 全部历史已登记干预数（KPI「已干预」）。 */
    long countByInterveneRegisteredTrue();

    /** 某批次某风险等级计数（高/中风险 KPI 真实计数）。 */
    long countByBatchNoAndRiskLevel(String batchNo, String riskLevel);

    /** 最近一次运行的任一行（据此取当前批次号，流失预警无周期维度，最近一批即当前榜）。 */
    Optional<AiChurnPrediction> findFirstByOrderByPredictionIdDesc();
}
