package com.meiyun.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 渠道账单（pay_channel_bill，B12 域一）：微信/支付宝/银行结算单 CSV 导入后的账实核对原料。
 *
 * <p>金额铁律：txn_amount/fee_amount/net_amount 均 Long「分」。order_no 为渠道商户单号
 * （勾兑键）；手续费行无订单号时 order_no 可空。bill_status：SUCCESS 交易成功 / REFUND 退款 /
 * FAILED 失败（失败行参与勾兑但通常不计入入账合计，由勾兑聚合口径处理）。
 *
 * <p>幂等：UNIQUE(channel_code, order_no, settle_batch)，同批次重复导入不双算；
 * import_batch 记录导入批次号（整批校验失败不落任何行）。
 *
 * <p><b>资金红线</b>：账单只写本台账供勾兑列示，绝不据账单伪造实付渠道分录。
 */
@Entity
@Table(name = "pay_channel_bill",
        uniqueConstraints = @UniqueConstraint(name = "uk_pay_channel_bill",
                columnNames = {"channel_code", "order_no", "settle_batch"}))
@Getter @Setter @NoArgsConstructor
public class PayChannelBill {

    /** 账单行号：PCB + yyyyMMdd + - + 6 位序号。 */
    @Id
    @Column(name = "bill_id", length = 24)
    private String billId;

    /** 渠道码：wxpay/alipay/transfer。 */
    @Column(name = "channel_code", nullable = false, length = 16)
    private String channelCode;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 渠道订单/商户单号（勾兑键；手续费等无订单行可空）。 */
    @Column(name = "order_no", length = 24)
    private String orderNo;

    /** 交易金额（分）。 */
    @Column(name = "txn_amount", nullable = false)
    private Long txnAmount;

    /** 手续费（分）。 */
    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    /** 实际到账（分）= 交易金额 − 手续费（导入时校验/补算）。 */
    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    /** SUCCESS / REFUND / FAILED。 */
    @Column(name = "bill_status", nullable = false, length = 16)
    private String billStatus;

    /** 渠道交易时间。 */
    @Column(name = "bill_time", nullable = false)
    private OffsetDateTime billTime;

    /** 结算批次号（渠道侧结算单号/日期批次）。 */
    @Column(name = "settle_batch", nullable = false, length = 32)
    private String settleBatch;

    /** 导入批次号（幂等：同批次重复导入整批跳过）。 */
    @Column(name = "import_batch", nullable = false, length = 24)
    private String importBatch;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (feeAmount == null) feeAmount = 0L;
    }
}
