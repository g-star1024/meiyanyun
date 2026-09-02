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

    @Column(name = "product_type", nullable = false, length = 8)
    private String productType;   // 实物 | 项目 | 权益

    @Column(name = "points_price", nullable = false)
    private Integer pointsPrice;

    @Column(nullable = false)
    private Integer stock;

    @Column(nullable = false, length = 8)
    private String status;        // 已上架 | 已下架

    @Column(length = 128)
    private String cover;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
