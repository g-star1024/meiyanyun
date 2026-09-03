package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 直播场次（M5-05 直播团购）。
 *
 * <p>字段对齐前端 mock 活规格（stores/m5Live.ts LiveSession）：
 * - platform：DOUYIN 抖音 / WECHAT_CHANNEL 视频号；
 * - status 状态机：NOT_STARTED 未开始 → LIVE 直播中 → ENDED 已结束（单向）；
 * - startTime：开播时间（LocalDateTime，前端 yyyy-MM-dd HH:mm 口径）；
 * - 漏斗：viewers 观看 / linkClicks 挂链点击 / dealCount 成交单数；
 * - dealAmount：挂链成交额，bigint 存「分」；
 * - mountedCouponIds：挂载券 ID JSON 数组（券数据来自券模板接口）；
 * - host：主播（创建时取当前登录人）。
 */
@Entity
@Table(name = "live_session")
@Getter @Setter @NoArgsConstructor
public class LiveSession {

    @Id
    @Column(name = "session_id", length = 24)
    private String sessionId;

    @Column(nullable = false, length = 64)
    private String title;

    /** 平台：DOUYIN / WECHAT_CHANNEL。 */
    @Column(nullable = false, length = 16)
    private String platform;

    /** 状态：NOT_STARTED / LIVE / ENDED。 */
    @Column(nullable = false, length = 12)
    private String status;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private Integer viewers;

    @Column(name = "link_clicks", nullable = false)
    private Integer linkClicks;

    @Column(name = "deal_count", nullable = false)
    private Integer dealCount;

    /** 挂链成交额（分）。 */
    @Column(name = "deal_amount", nullable = false)
    private Long dealAmount;

    /** 挂载券 ID JSON 数组。 */
    @Column(name = "mounted_coupon_ids", nullable = false, length = 512)
    private String mountedCouponIds;

    @Column(length = 500)
    private String intro;

    @Column(length = 32)
    private String host;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
