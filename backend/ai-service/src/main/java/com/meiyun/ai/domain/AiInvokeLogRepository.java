package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface AiInvokeLogRepository extends JpaRepository<AiInvokeLog, Long> {

    Page<AiInvokeLog> findAllByOrderByLogIdDesc(Pageable pageable);

    @Query("""
            select l from AiInvokeLog l
            where (:featureCode is null or l.featureCode = :featureCode)
              and (:success is null or l.success = :success)
            order by l.logId desc
            """)
    Page<AiInvokeLog> search(@Param("featureCode") String featureCode,
                             @Param("success") Boolean success,
                             Pageable pageable);

    long countBySuccess(Boolean success);

    long countByInvokedAtGreaterThanEqual(OffsetDateTime since);

    long countByInvokedAtGreaterThanEqualAndSuccess(OffsetDateTime since, Boolean success);

    long countByInvokedAtGreaterThanEqualAndFeatureCode(OffsetDateTime since, String featureCode);

    long countByInvokedAtGreaterThanEqualAndModelCode(OffsetDateTime since, String modelCode);

    @Query("select coalesce(sum(l.costFen),0) from AiInvokeLog l")
    long sumCostFen();

    @Query("select coalesce(sum(l.totalTokens),0) from AiInvokeLog l")
    long sumTokens();

    @Query(value = """
            select percentile_cont(0.99) within group (order by latency_ms)
            from ai_invoke_log
            where invoked_at >= :since and latency_ms is not null
            """, nativeQuery = true)
    Double p99LatencySince(@Param("since") OffsetDateTime since);

    interface FeatureCost {
        String getFeatureCode();

        Long getCalls();

        Long getTokens();

        Long getCostFen();
    }

    @Query(value = """
            select feature_code as featureCode,
                   count(*) as calls,
                   coalesce(sum(total_tokens), 0) as tokens,
                   coalesce(sum(cost_fen), 0) as costFen
            from ai_invoke_log
            group by feature_code
            order by calls desc
            """, nativeQuery = true)
    List<FeatureCost> costByFeature();

    interface MetricAgg {
        Long getCalls();

        Long getSuccessCalls();

        Long getTokens();

        Long getCostFen();
    }

    /** 时间窗内按功能或模型聚合调用侧技术指标（featureCode/modelCode 互斥，皆空为全局）。 */
    @Query(value = """
            select count(*) as calls,
                   coalesce(sum(case when success then 1 else 0 end), 0) as successCalls,
                   coalesce(sum(total_tokens), 0) as tokens,
                   coalesce(sum(cost_fen), 0) as costFen
            from ai_invoke_log
            where invoked_at >= :since
              and (cast(:featureCode as text) is null or feature_code = :featureCode)
              and (cast(:modelCode as text) is null or model_code = :modelCode)
            """, nativeQuery = true)
    MetricAgg aggregateSince(@Param("since") OffsetDateTime since,
                             @Param("featureCode") String featureCode,
                             @Param("modelCode") String modelCode);

    /** 同窗口 P99 延迟（ms），支持按功能或模型过滤。 */
    @Query(value = """
            select percentile_cont(0.99) within group (order by latency_ms)
            from ai_invoke_log
            where invoked_at >= :since and latency_ms is not null
              and (cast(:featureCode as text) is null or feature_code = :featureCode)
              and (cast(:modelCode as text) is null or model_code = :modelCode)
            """, nativeQuery = true)
    Double p99LatencySince(@Param("since") OffsetDateTime since,
                           @Param("featureCode") String featureCode,
                           @Param("modelCode") String modelCode);
}
