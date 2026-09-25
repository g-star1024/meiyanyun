package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 店×月运营指标事实表（T2 数据分析专项 D5，P5-B97）。
 *
 * <p>7 计数（新客/复购/治疗人次/活跃/到院/咨询/成交）＋3 财务快照（revenue/cost/gross_profit
 * 冗余自 revenue_monthly）单表自足，group-overview 月度投影单源直读。列可空：
 * 无源指标留 null，前端显「—」不伪造。由 {@link MonthlyMetricJob} 月游标物化
 * （每月 1 日 02:30 结算上月＋每日 03:30 刷新当月），ddl-auto 建表零 Flyway。
 */
@Entity
@Table(name = "monthly_store_metrics")
@IdClass(MonthlyStoreMetric.MonthlyStoreMetricId.class)
@Getter @Setter @NoArgsConstructor
public class MonthlyStoreMetric {

    @Id
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Id
    @Column(name = "period_month")
    private LocalDate periodMonth;

    /** 当月新客数（同店同客户首张 paid 订单落在当月）。 */
    @Column(name = "new_customers")
    private Long newCustomers;

    /** 当月首达复购的客户数（同店同客户累计 paid≥2，达标本月计 1）。 */
    @Column(name = "repurchase_count")
    private Long repurchaseCount;

    /** 当月治疗人次（writeoff 状态 DONE 计数）。 */
    @Column(name = "treatment_count")
    private Long treatmentCount;

    /** 当月活跃客户（有 arrival 或 paid 订单的去重客户数）。 */
    @Column(name = "active_customers")
    private Long activeCustomers;

    /** 当月到院人次（arrival 计数）。 */
    @Column(name = "arrival_count")
    private Long arrivalCount;

    /** 当月咨询数（consult_plan 非 ABANDONED 计数）。 */
    @Column(name = "consult_count")
    private Long consultCount;

    /** 当月成交单数（paid 订单计数）。 */
    @Column(name = "deal_count")
    private Long dealCount;

    /** 当月营收快照（分，冗余自 revenue_monthly，无行留 null）。 */
    @Column(name = "revenue")
    private Long revenue;

    /** 当月成本快照（分，冗余自 revenue_monthly，无行留 null）。 */
    @Column(name = "cost")
    private Long cost;

    /** 当月毛利快照（分，冗余自 revenue_monthly，无行留 null）。 */
    @Column(name = "gross_profit")
    private Long grossProfit;

    /** 本轮物化时间（月游标 Job 写入，验收取数对账用）。 */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    /** 复合主键（普通 POJO，避免 record 与 Hibernate IdClass 不兼容）。 */
    public static class MonthlyStoreMetricId implements java.io.Serializable {
        private String storeCode;
        private LocalDate periodMonth;

        public MonthlyStoreMetricId() {
        }

        public MonthlyStoreMetricId(String storeCode, LocalDate periodMonth) {
            this.storeCode = storeCode;
            this.periodMonth = periodMonth;
        }

        public String getStoreCode() {
            return storeCode;
        }

        public LocalDate getPeriodMonth() {
            return periodMonth;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MonthlyStoreMetricId)) return false;
            MonthlyStoreMetricId that = (MonthlyStoreMetricId) o;
            return java.util.Objects.equals(storeCode, that.storeCode)
                    && java.util.Objects.equals(periodMonth, that.periodMonth);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(storeCode, periodMonth);
        }
    }
}
