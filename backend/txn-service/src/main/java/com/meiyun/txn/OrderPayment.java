package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 订单支付明细流水（order_payment）。一笔订单可有多笔（组合支付 / 部分收款）。
 *
 * <p>金额铁律（单位「分」），对齐前端收银台 mock（活规格）：
 * <ul>
 *   <li>非现金（微信/支付宝/银行卡/会员储值）：客户实付 = 入账额，且入账后累计不得超过应收（超额拒绝）。</li>
 *   <li>现金：录入的是「客户实付 cash_tendered」，入账额按待收封顶（min(实付, 待收)），
 *       找零 change = 实付 − 入账；收齐后多付的现金当场找零，不形成预收/余额。</li>
 *   <li>累计入账额 ≥ 订单应收 → 订单置「已收款」。</li>
 * </ul>
 */
@Entity
@Table(name = "order_payment")
@Getter @Setter @NoArgsConstructor
public class OrderPayment {

    /** 支付流水号：PM + yyyyMMdd + - + 6 位序号。 */
    @Id
    @Column(name = "payment_id", length = 24)
    private String paymentId;

    /** 关联订单号（txn_order.order_no）。 */
    @Column(name = "order_no", nullable = false, length = 24)
    private String orderNo;

    /** 支付方式：cash/card/wxpay/alipay/balance。 */
    @Column(name = "pay_method", nullable = false, length = 16)
    private String payMethod;

    /** 客户实付金额（分）。现金时为客户递交的现金；非现金等于入账额。 */
    @Column(name = "cash_tendered", nullable = false)
    private Long cashTendered;

    /** 实际入账金额（分）。现金按待收封顶；找零 = cashTendered − postedAmount。 */
    @Column(name = "posted_amount", nullable = false)
    private Long postedAmount;

    /** 本笔现金找零（分），非现金为 0。 */
    @Column(name = "change_amount", nullable = false)
    private Long changeAmount;

    /** 本笔后订单累计已入账（分），便于对账/展示。 */
    @Column(name = "paid_after", nullable = false)
    private Long paidAfter;

    /** 收银员 / 操作人。 */
    @Column(length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (changeAmount == null) changeAmount = 0L;
    }
}
