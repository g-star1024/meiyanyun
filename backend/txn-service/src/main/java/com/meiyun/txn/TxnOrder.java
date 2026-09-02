package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 订单（M4-10 开单 / M4-13 订单确认）。禁忌核验四态 contra_check。
 */
@Entity
@Table(name = "txn_order")
@Getter @Setter @NoArgsConstructor
public class TxnOrder {

    @Id
    @Column(name = "order_no", length = 24)
    private String orderNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(nullable = false, length = 64)
    private String project;

    @Column(nullable = false)
    private Long amount;

    @Column(length = 32)
    private String consultant;

    @Column(name = "contra_check", nullable = false, length = 6)
    private String contraCheck;               // GREEN/YELLOW/RED

    @Column(name = "contra_detail", columnDefinition = "TEXT")
    private String contraDetail;

    @Column(name = "exemption_sign1", length = 32)
    private String exemptionSign1;

    @Column(name = "exemption_sign2", length = 32)
    private String exemptionSign2;

    @Column(length = 32)
    private String sign1;

    @Column(length = 32)
    private String sign2;

    @Column(nullable = false, length = 8)
    private String status;                    // 待签核/待收款/已收款/已核销/已取消

    /** 整单核销时刻（已收款订单核销回写；未核销为空）。 */
    @Column(name = "writeoff_at")
    private OffsetDateTime writeoffAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (contraCheck == null) contraCheck = "GREEN";
        if (status == null) status = "待签核";
    }
}
