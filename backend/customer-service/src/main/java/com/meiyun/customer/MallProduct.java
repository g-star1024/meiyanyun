package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** M3-20 积分商城商品（B 端配置，C 端小程序/App 消费）。 */
@Entity
@Table(name = "mall_product")
@Getter @Setter @NoArgsConstructor
public class MallProduct {

    @Id
    @Column(name = "product_id", length = 24)
    private String productId;

    @Column(name = "product_name", nullable = false, length = 64)
    private String productName;

    @Column(name = "product_type", nullable = false, length = 16)
    private String productType;   // 项目 | 实物 | 优惠券 | 服务

    @Column(name = "points_price", nullable = false)
    private Integer pointsPrice;

    @Column(nullable = false)
    private Integer stock;       // -1 表示不限库存（优惠券类）

    @Column(nullable = false, length = 8)
    private String status;        // 已上架 | 已下架（低库存≤50 为前端派生，不入库）

    @Column(length = 128)
    private String cover;

    /** 商品说明（详情展示：规格/使用规则/有效期等），新建/编辑可填。 */
    @Column(length = 256)
    private String description;

    /** 累计已兑数量（审核通过/履约时累加，商品列表「已兑」列读模型）。 */
    @Column(name = "redeemed_count", nullable = false)
    private Integer redeemedCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
