package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 海报模板（M5-04 海报裂变）。
 *
 * <p>字段对齐前端 mock 活规格（stores/m5Poster.ts）：
 * - style：FESTIVAL 节日促销 / NEWBIE 新客礼 / PROJECT 项目种草 / MEMBER 会员日 /
 *   REFERRAL 转介绍 / LIVE 直播预约；
 * - status：ENABLED 启用中 / DISABLED 已停用；
 * - uses：累计使用次数（每生成一张海报 +1）；
 * - accent：封面视觉色（brand/teal/orange/purple/blue/gold）。
 */
@Entity
@Table(name = "poster_template")
@Getter @Setter @NoArgsConstructor
public class PosterTemplate {

    @Id
    @Column(name = "template_id", length = 24)
    private String templateId;

    @Column(name = "template_name", nullable = false, length = 64)
    private String templateName;

    /** 风格：FESTIVAL/NEWBIE/PROJECT/MEMBER/REFERRAL/LIVE。 */
    @Column(nullable = false, length = 12)
    private String style;

    /** 状态：ENABLED 启用中 / DISABLED 已停用。 */
    @Column(nullable = false, length = 8)
    private String status;

    @Column(nullable = false)
    private Integer uses;

    /** 视觉色：brand/teal/orange/purple/blue/gold。 */
    @Column(nullable = false, length = 8)
    private String accent;

    @Column(name = "default_title", nullable = false, length = 64)
    private String defaultTitle;

    @Column(name = "default_subtitle", nullable = false, length = 128)
    private String defaultSubtitle;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
