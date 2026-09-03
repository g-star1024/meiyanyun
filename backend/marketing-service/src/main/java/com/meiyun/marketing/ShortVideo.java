package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 短视频（M5-05 直播团购 · 短视频库，本期只读）。
 *
 * <p>字段对齐前端 mock 活规格（stores/m5Live.ts ShortVideo）：
 * - platform：DOUYIN 抖音 / WECHAT_CHANNEL 视频号 / XIAOHONGSHU 小红书；
 * - plays 播放量 / likes 点赞 / dealCount 挂链成交单数 / dealAmount 成交额（分）；
 * - tags：标签 JSON 文本数组；publishedAt：发布日期。
 */
@Entity
@Table(name = "short_video")
@Getter @Setter @NoArgsConstructor
public class ShortVideo {

    @Id
    @Column(name = "video_id", length = 24)
    private String videoId;

    @Column(nullable = false, length = 64)
    private String title;

    /** 平台：DOUYIN / WECHAT_CHANNEL / XIAOHONGSHU。 */
    @Column(nullable = false, length = 16)
    private String platform;

    @Column(nullable = false)
    private Integer plays;

    @Column(nullable = false)
    private Integer likes;

    @Column(name = "deal_count", nullable = false)
    private Integer dealCount;

    /** 挂链成交额（分）。 */
    @Column(name = "deal_amount", nullable = false)
    private Long dealAmount;

    /** 标签 JSON 文本数组。 */
    @Column(nullable = false, length = 256)
    private String tags;

    @Column(name = "published_at", nullable = false)
    private LocalDate publishedAt;
}
