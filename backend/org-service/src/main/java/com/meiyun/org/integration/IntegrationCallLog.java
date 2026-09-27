package com.meiyun.org.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T3 数据中台 连接器调用日志（DESIGN-T3 §三，表 integration_call_log / V71）。
 * uk(connector_id, transaction_id) 幂等锚；当前仅 OUT 方向，IN 方向留 DESIGN-T3 §7 移交登记。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "integration_call_log")
public class IntegrationCallLog {

    public static final String DIRECTION_OUT = "OUT";
    public static final String DIRECTION_IN = "IN";

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_ACK = "ACK";
    public static final String STATUS_FAIL = "FAIL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connector_id", nullable = false)
    private Long connectorId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "direction", nullable = false, length = 4)
    private String direction;

    @Column(name = "method", length = 8)
    private String method;

    @Column(name = "endpoint", length = 256)
    private String endpoint;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "status", nullable = false, length = 8)
    private String status;

    @Column(name = "error_msg", length = 256)
    private String errorMsg;

    @Column(name = "request_at", nullable = false)
    private OffsetDateTime requestAt;
}
