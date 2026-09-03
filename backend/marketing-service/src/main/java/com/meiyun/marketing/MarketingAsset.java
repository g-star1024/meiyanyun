package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 营销素材（M5-13 素材库）。
 *
 * <p>本期只做元数据管理与状态流转（记录/标签/授权分发），不接 S3/MinIO 真实文件流。
 * 字段对齐前端 mock 活规格（stores/m5Assets.ts）：
 * - type：IMAGE 图片(海报) / VIDEO 视频 / COPY 文案 / LOGO Logo；
 * - tags：标签 JSON 文本数组（["暑期","水光"]）；
 * - scope：ALL 全部门店 / SPECIFIED 指定门店；scope=ALL 时 storeCodes 存空数组，
 *   授权门店名单由前端适配层按门店主数据展开（全部门店）；
 * - storeCodes：授权门店编码 JSON 数组（["SST01","SST02"]），门店名经 store-service 解析；
 * - accent：卡片视觉色（brand/teal/orange/purple/blue/gold），落库以稳定种子视觉；
 * - content：COPY 文案正文，可空；
 * - refCount：被引用次数（活动/直播/落地页），本期由种子携带，不做引用反写。
 */
@Entity
@Table(name = "marketing_asset")
@Getter @Setter @NoArgsConstructor
public class MarketingAsset {

    @Id
    @Column(name = "asset_id", length = 24)
    private String assetId;

    @Column(name = "asset_name", nullable = false, length = 64)
    private String assetName;

    /** 类型：IMAGE 图片 / VIDEO 视频 / COPY 文案 / LOGO Logo。 */
    @Column(nullable = false, length = 8)
    private String type;

    /** 标签 JSON 文本数组。 */
    @Column(nullable = false, length = 512)
    private String tags;

    /** 授权范围：ALL 全部门店 / SPECIFIED 指定门店。 */
    @Column(nullable = false, length = 10)
    private String scope;

    /** 授权门店编码 JSON 数组（scope=SPECIFIED 时生效）。 */
    @Column(name = "store_codes", nullable = false, length = 512)
    private String storeCodes;

    @Column(name = "expire_at", nullable = false)
    private LocalDate expireAt;

    @Column(name = "ref_count", nullable = false)
    private Integer refCount;

    /** 视觉色：brand/teal/orange/purple/blue/gold。 */
    @Column(nullable = false, length = 8)
    private String accent;

    /** COPY 文案正文（可空）。 */
    @Column(length = 1000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
