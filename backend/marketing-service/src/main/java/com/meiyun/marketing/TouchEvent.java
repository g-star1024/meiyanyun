package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 触点事件快照（P5-B98 / DESIGN-T2 §3-D4）。
 *
 * <p>touch_type 五值：LANDING_VISIT 落地页访问 / LANDING_LEAD 落地页留资 /
 * POSTER_SCAN 海报扫码（§7 待 C 端）/ PUSH_SEND 推送发送 / RETURNBACK 渠道回传。
 * <b>仅存不算</b>——营销归因算法 B69 定案延后，本表仅落快照，供后续归因与 T2-01 采集监控直读。
 *
 * <p>幂等：(client_token, touch_type) 部分唯一索引（V52，照 V46 落地页先例）——
 * 采集重放/并发撞唯一约束即视为重复，由 {@link TouchEventRecorder} 查重 + 调用方捕异常兜底。
 *
 * <p>channel 取值：LANDING 落地页 / SMS|WECOM|WECHAT_MP 推送渠道 / DOUYIN|RED|MEITUAN 广告渠道 / POSTER 海报。
 * ref_type/ref_id 溯源：LANDING_PAGE(page_id) / PUSH_RECORD(push_id) / CHANNEL_RETURNBACK(id)。
 * 列映射与 V52 DDL 逐列对齐（ddl-auto=validate 硬约束，B48 卡3 收口）。
 */
@Entity
@Table(name = "touch_event")
@Getter @Setter @NoArgsConstructor
public class TouchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 客户号（可空：匿名访问/留资未匹配）。 */
    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 触点渠道：LANDING / SMS / WECOM / WECHAT_MP / DOUYIN / RED / MEITUAN / POSTER。 */
    @Column(nullable = false, length = 32)
    private String channel;

    /** 触点类型：LANDING_VISIT / LANDING_LEAD / POSTER_SCAN / PUSH_SEND / RETURNBACK。 */
    @Column(name = "touch_type", nullable = false, length = 16)
    private String touchType;

    /** 来源单据类型：LANDING_PAGE / PUSH_RECORD / CHANNEL_RETURNBACK。 */
    @Column(name = "ref_type", length = 32)
    private String refType;

    /** 来源单据号（page_id / push_id / channel_returnback.id）。 */
    @Column(name = "ref_id", length = 64)
    private String refId;

    /** 采集幂等令牌（落地页访客端生成；(client_token,touch_type) 部分唯一）。 */
    @Column(name = "client_token", length = 64)
    private String clientToken;

    /** 触点原始快照 JSON（jsonb 映射照本服务 Recall/MarketingCfg/AutomationRule 先例）。 */
    @Column(columnDefinition = "jsonb")
    private String payload;

    /** 触点发生时刻（落库时刻）。 */
    @Column(name = "at", nullable = false)
    private OffsetDateTime at;

    @PrePersist
    void prePersist() {
        if (at == null) at = OffsetDateTime.now();
    }
}
