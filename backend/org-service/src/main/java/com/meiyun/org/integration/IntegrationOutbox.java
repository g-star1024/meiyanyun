package com.meiyun.org.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T3 数据中台 出站消息（DESIGN-T3 §三，表 integration_outbox / V72）。
 * 单向镜像红线：只从本地业务表向外推送镜像，绝不反向写资金池。
 * uk(connector_id, txn_no) 幂等锚：同步重放不重复落库不重复外呼。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "integration_outbox")
public class IntegrationOutbox {

    /** 业务类型六枚举（本批仅 ORDER_PAY 已支付单）。 */
    public static final String BIZ_ORDER_PAY = "ORDER_PAY";
    public static final String BIZ_REFUND = "REFUND";
    public static final String BIZ_INVOICE = "INVOICE";
    public static final String BIZ_VOUCHER = "VOUCHER";
    public static final String BIZ_CONTACT = "CONTACT";
    public static final String BIZ_AD_CLICK = "AD_CLICK";

    /** 状态机：PENDING→ACK/FAILED→MATCHED；LONG/SHORT 无三方账单源当前不产生（留 §7）。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACK = "ACK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_MATCHED = "MATCHED";
    public static final String STATUS_LONG = "LONG";
    public static final String STATUS_SHORT = "SHORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outbox_no", nullable = false, length = 40)
    private String outboxNo;

    @Column(name = "connector_id", nullable = false)
    private Long connectorId;

    @Column(name = "biz_type", nullable = false, length = 16)
    private String bizType;

    @Column(name = "txn_no", nullable = false, length = 64)
    private String txnNo;

    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "local_sent", nullable = false)
    private Boolean localSent;

    @Column(name = "remote_ack", nullable = false)
    private Boolean remoteAck;

    @Column(name = "reconciled", nullable = false)
    private Boolean reconciled;

    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "error_msg", length = 256)
    private String errorMsg;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "reconciled_at")
    private OffsetDateTime reconciledAt;
}
