package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 划扣核销（M4-14）：账实校验——扣次/扣额须与卡余额/次数一致。
 */
@Entity
@Table(name = "writeoff_record")
@Getter @Setter @NoArgsConstructor
public class WriteoffRecord {

    @Id
    @Column(name = "writeoff_id", length = 24)
    private String writeoffId;

    @Column(name = "order_no", length = 24)
    private String orderNo;

    @Column(name = "card_no", length = 24)
    private String cardNo;

    @Column(name = "customer_id", length = 16)
    private String customerId;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(nullable = false, length = 64)
    private String project;

    @Column(name = "times_used", nullable = false)
    private Integer timesUsed;

    @Column(nullable = false)
    private Long amount;

    @Column(length = 32)
    private String operator;

    /** 订单核销（/writeoff 页）记录状态：DONE=已核销、ABNORMAL=异常、VOID=已作废；卡扣次划扣记录为空（由 M2 划扣执行台消费）。 */
    @Column(length = 16)
    private String status;

    /** 异常/作废原因（订单核销记录用）。 */
    @Column(name = "abnormal_reason", length = 255)
    private String abnormalReason;

    @Column(length = 32)
    private String sign1;

    @Column(length = 32)
    private String sign2;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (timesUsed == null) timesUsed = 1;
        if (amount == null) amount = 0L;
    }
}
