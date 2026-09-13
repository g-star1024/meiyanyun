package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiRepurchasePredictionRepository extends JpaRepository<AiRepurchasePrediction, Long> {

    /** 某批次榜单：按复购概率倒序。 */
    List<AiRepurchasePrediction> findByBatchNoOrderByProbDescPredictionIdAsc(String batchNo);

    /** 某批次 + 品类筛选榜单。 */
    List<AiRepurchasePrediction> findByBatchNoAndProjectCodeOrderByProbDescPredictionIdAsc(
            String batchNo, String projectCode);

    long countByBatchNo(String batchNo);

    /** 当日批次计数（前缀 RP+yyyyMMdd），用于批次号自增序号。 */
    long countByBatchNoStartingWith(String prefix);

    long countByBatchNoAndFollowupRegisteredTrue(String batchNo);

    /** 全部历史已登记建跟进数（KPI「已建任务」）。 */
    long countByFollowupRegisteredTrue();

    /** 某周期最近一次运行的任一行（据此取当前批次号）。 */
    Optional<AiRepurchasePrediction> findFirstByPeriodOrderByPredictionIdDesc(String period);
}
