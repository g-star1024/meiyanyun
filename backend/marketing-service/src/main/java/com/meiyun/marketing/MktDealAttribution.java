package com.meiyun.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 成交归属登记（P5-B92，V51 建表）：订单 → 直播场次/短视频归属。
 *
 * <p>{@link DealBackfillJob} 轮询 txn 已收款订单投影，对带成交来源（sourceType∈
 * {LIVE_SESSION, SHORT_VIDEO}）的订单落本表一行并正向增量场次/视频成交计数；
 * 退款段命中本表 PAID 行则置 REFUNDED 并负向冲销（floor 0）。
 *
 * <p>幂等锚 uk_mkt_deal_attr_order(order_no)：一笔订单至多归属一次，邻轮窗口重叠
 * 重复出现的订单静默跳过。POSTER 词表预留（本期无写入方，登记 04 Backlog）。
 * 金额口径：amount bigint 存「分」，以 paid-orders 快照为准。
 */
@Entity
@Table(name = "mkt_deal_attribution")
@Getter
@Setter
@NoArgsConstructor
public class MktDealAttribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单号（txn_order.order_no），全表唯一幂等锚。 */
    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    /** 来源类型：LIVE_SESSION 直播场次 / SHORT_VIDEO 短视频（POSTER 预留）。 */
    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType;

    /** 来源标识：场次号（LS...）/ 视频号（SV...），逻辑引用不建物理外键。 */
    @Column(name = "source_id", nullable = false, length = 24)
    private String sourceId;

    /** 成交额（分），以 txn paid-orders 快照为准。 */
    @Column(name = "amount", nullable = false)
    private Long amount;

    /** PAID 已收款归属 / REFUNDED 已退款冲销。 */
    @Column(name = "status", nullable = false, length = 10)
    private String status = "PAID";

    @Column(name = "paid_at", nullable = false)
    private OffsetDateTime paidAt;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }
}
