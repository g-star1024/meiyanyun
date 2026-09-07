package com.meiyun.store.pricelist;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 门店价目（B14，门店级，DESIGN-P5 §3.4）。
 * 门店对集团 SKU 的定价：原价（划线）/会员价（执行价）/活动价；同店同 SKU 唯一。
 * 调价三态状态机：ACTIVE 启用 ──change-request──▶ PENDING 待审批 ──approve──▶ ACTIVE（新价生效）
 *                                              └──reject──▶ ACTIVE（原价保留）；
 * DISABLED 停用经 toggle 与 ACTIVE 互切；PENDING 态不可再提交/不可切换/不可重复审批。
 * pending_* 为待审批价（分），审批通过后覆盖正式价并清空。
 */
@Entity
@Table(name = "store_price",
        uniqueConstraints = @UniqueConstraint(name = "uk_price_store_sku",
                columnNames = {"store_code", "sku"}))
@Getter
@Setter
@NoArgsConstructor
public class StorePrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 门店码（SST01 等）；空串为集团指导价模板（本批不播种模板行） */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 逻辑外键 product_sku.sku（价目行编码 = SKU 编码） */
    @Column(nullable = false, length = 40)
    private String sku;

    /** 原价/划线价（分） */
    @Column(name = "original_price_fen", nullable = false)
    private Long originalPriceFen;

    /** 会员价/执行价（分） */
    @Column(name = "member_price_fen", nullable = false)
    private Long memberPriceFen;

    /** 活动价（分，可空） */
    @Column(name = "promo_price_fen")
    private Long promoPriceFen;

    /** ACTIVE 启用 / DISABLED 停用 / PENDING 待审批 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 待审批会员价（分） */
    @Column(name = "pending_member_price_fen")
    private Long pendingMemberPriceFen;

    /** 待审批活动价（分，可空） */
    @Column(name = "pending_promo_price_fen")
    private Long pendingPromoPriceFen;

    /** 调价原因 */
    @Column(name = "pending_reason", length = 255)
    private String pendingReason;

    /** 申请人（JWT staffName） */
    @Column(name = "requested_by", length = 32)
    private String requestedBy;

    /** 申请时间 */
    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "ACTIVE";
        if (originalPriceFen == null) originalPriceFen = 0L;
        if (memberPriceFen == null) memberPriceFen = 0L;
    }
}
