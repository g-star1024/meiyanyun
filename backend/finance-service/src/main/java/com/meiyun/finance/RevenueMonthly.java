package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 门店月度营收/成本/毛利（revenue = cost + gross_profit 由 DB CHECK 约束）。 */
@Entity
@Table(name = "revenue_monthly")
@IdClass(RevenueMonthly.RevenueMonthlyId.class)
@Getter @Setter @NoArgsConstructor
public class RevenueMonthly {

    @Id
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Id
    @Column(name = "period_month")
    private LocalDate periodMonth;

    @Column(nullable = false)
    private Long revenue;

    @Column(nullable = false)
    private Long cost;

    @Column(name = "gross_profit", nullable = false)
    private Long grossProfit;

    @Column(name = "cost_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal costRate;

    @Column(name = "gross_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal grossRate;

    /** 复合主键（普通 POJO，避免 record 与 Hibernate IdClass 不兼容）。 */
    public static class RevenueMonthlyId implements java.io.Serializable {
        private String storeCode;
        private LocalDate periodMonth;

        public RevenueMonthlyId() {
        }

        public RevenueMonthlyId(String storeCode, LocalDate periodMonth) {
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
            if (!(o instanceof RevenueMonthlyId)) return false;
            RevenueMonthlyId that = (RevenueMonthlyId) o;
            return java.util.Objects.equals(storeCode, that.storeCode)
                    && java.util.Objects.equals(periodMonth, that.periodMonth);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(storeCode, periodMonth);
        }
    }
}
