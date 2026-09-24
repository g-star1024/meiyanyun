package com.meiyun.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 成交回写游标（P5-B92，V51 建表，单行 id=1，镜像 auto_grant_state）。
 *
 * <p>paid/refund 分段游标：记录上次成功处理窗口上界，保证 {@link DealBackfillJob}
 * 断点续跑；段成功才推进对应游标，段故障不推进、下轮按原窗口自愈。
 * 首次运行（NULL）→ 远早于系统的下界（2000-01-01）全量回填，重放由
 * mkt_deal_attribution.uk_mkt_deal_attr_order 幂等吞掉；表结构与 V51 DDL 严格一致
 *（marketing ddl-auto=validate，无 updated_at 列）。
 */
@Entity
@Table(name = "deal_backfill_state")
@Getter
@Setter
@NoArgsConstructor
public class DealBackfillState {

    @Id
    @Column(name = "id")
    private Short id = 1;

    /** paid 段上次成功窗口上界（created_at ≤ 该值的已收款订单已处理）。NULL=从未运行。 */
    @Column(name = "last_paid_at")
    private OffsetDateTime lastPaidAt;

    /** refund 段上次成功窗口上界。NULL=从未运行。 */
    @Column(name = "last_refunded_at")
    private OffsetDateTime lastRefundedAt;
}
