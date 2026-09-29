package com.meiyun.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_t4_gpu_node")
@Getter
@Setter
@NoArgsConstructor
public class AiT4GpuNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "node_id")
    private Long nodeId;

    /** 业务码（gpu-*，与 name 同值，前端主键锚） */
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    /** A100 / H100 / V100 / T4（DB 层不加 chk，留新型号扩展） */
    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "vram_total", nullable = false)
    private Integer vramTotal = 0;

    @Column(name = "vram_used", nullable = false)
    private Integer vramUsed = 0;

    @Column(name = "utilization", nullable = false)
    private Integer utilization = 0;

    @Column(name = "temperature", nullable = false)
    private Integer temperature = 0;

    /** IDLE / BUSY / OFFLINE / RESERVED */
    @Column(name = "status", nullable = false)
    private String status = "IDLE";

    @Column(name = "current_task")
    private String currentTask;

    @Column(name = "pod_name")
    private String podName;

    @Column(name = "cost_per_hour", nullable = false)
    private BigDecimal costPerHour = BigDecimal.ZERO;

    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
