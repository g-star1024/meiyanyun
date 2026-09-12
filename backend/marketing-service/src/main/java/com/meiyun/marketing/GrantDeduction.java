package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 赠金抵扣流水（域⑤ 赠金收银台抵扣）。一次订单抵扣可跨多行 customer_grant（按到期时间 FIFO），
 * 故单独立账：一行流水 = 一张赠金券在一笔订单上的实际扣减额。
 *
 * <p>幂等键 biz_ref = 订单号（同订单重放不双扣，与 txn 储值扣款「同单单笔」口径一致）；
 * 一次抵扣写多行流水共用同一 biz_ref，(biz_ref, grant_id) 复合唯一防单券重复扣。
 *
 * <p>金额单位「分」，amount_fen &gt; 0 表示扣减额（正数记账，方向由 change_type 表达）。
 * 退款回加（REFUND 反向流水）留下游 Backlog，本期只做 DEDUCT。
 */
@Entity
@Table(name = "grant_deduction",
        uniqueConstraints = @UniqueConstraint(name = "uk_gd_ref_grant",
                columnNames = {"biz_ref", "grant_id"}))
@Getter
@Setter
@NoArgsConstructor
public class GrantDeduction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务幂等键：收银台抵扣为订单号 OD…。 */
    @Column(name = "biz_ref", nullable = false, length = 64)
    private String bizRef;

    /** 被扣减的赠金账本行 id。 */
    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    /** 本行实扣金额（分，&gt; 0）。 */
    @Column(name = "amount_fen", nullable = false)
    private Long amountFen;

    /** 该赠金行扣减后的剩余余额（分，用于对账回放）。 */
    @Column(name = "balance_after_fen", nullable = false)
    private Long balanceAfterFen;

    /** DEDUCT 抵扣（本期唯一取值）。 */
    @Column(name = "change_type", nullable = false, length = 16)
    private String changeType;

    /** 抵扣发生门店（取自订单，非赠金归属门店）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    /** 操作人（收银员工号，服务间调用透传；仅留痕）。 */
    @Column(length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
