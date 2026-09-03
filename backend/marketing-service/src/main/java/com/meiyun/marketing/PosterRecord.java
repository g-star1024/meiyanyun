package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 已生成海报（M5-04 海报裂变）。
 *
 * <p>字段对齐前端 mock 活规格（stores/m5Poster.ts Poster）：
 * - 裂变漏斗：share 分享 / scan 扫码 / lead 留资 / visit 到店 / deal 成交（5 级）；
 * - dealAmount：成交金额，bigint 存「分」（前端元口径由适配层换算）；
 * - commissionRate：分销佣金比例，百分比×10 整数（5% = 50，与券折扣 RATE 口径一致）；
 * - status：本期生成即 PUBLISHED（DRAFT 为前端展示态预留）；
 * - referrerName：分销推荐人姓名（文本，下拉数据来自老带新页面）。
 */
@Entity
@Table(name = "poster_record")
@Getter @Setter @NoArgsConstructor
public class PosterRecord {

    @Id
    @Column(name = "poster_id", length = 24)
    private String posterId;

    @Column(name = "template_id", nullable = false, length = 24)
    private String templateId;

    @Column(name = "template_name", nullable = false, length = 64)
    private String templateName;

    /** 风格（冗余自模板，便于列表展示）。 */
    @Column(nullable = false, length = 12)
    private String style;

    /** 视觉色（冗余自模板）。 */
    @Column(nullable = false, length = 8)
    private String accent;

    @Column(nullable = false, length = 64)
    private String title;

    @Column(length = 128)
    private String subtitle;

    /** 主推项目。 */
    @Column(nullable = false, length = 64)
    private String project;

    /** 分销推荐人姓名。 */
    @Column(name = "referrer_name", length = 32)
    private String referrerName;

    /** 状态：DRAFT 草稿 / PUBLISHED 已发布。 */
    @Column(nullable = false, length = 10)
    private String status;

    @Column(nullable = false)
    private Integer share;

    @Column(nullable = false)
    private Integer scan;

    @Column(nullable = false)
    private Integer lead;

    @Column(nullable = false)
    private Integer visit;

    @Column(nullable = false)
    private Integer deal;

    /** 成交金额（分）。 */
    @Column(name = "deal_amount", nullable = false)
    private Long dealAmount;

    /** 佣金比例，百分比×10（5% = 50）。 */
    @Column(name = "commission_rate", nullable = false)
    private Integer commissionRate;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
