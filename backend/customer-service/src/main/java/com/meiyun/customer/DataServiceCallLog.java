package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T2-B3 数据服务-调用日志（棒⑥卡7 T2-04，表 data_service_call_log / V71）。
 * 逐条上报·读时聚合 24h 窗口；集团级资产无 store_code 维度。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_service_call_log")
public class DataServiceCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "called_at", nullable = false)
    private OffsetDateTime calledAt;

    @Column(name = "latency_ms", nullable = false)
    private Integer latencyMs;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @PrePersist
    void prePersist() {
        if (calledAt == null) calledAt = OffsetDateTime.now();
        if (latencyMs == null) latencyMs = 0;
        if (success == null) success = Boolean.TRUE;
    }
}
