package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * DSAR（Data Subject Access Request）个人行使权利请求工单。
 *
 * PIPL 第 44-49 条刚性要求：客户有权访问/复制/更正/删除/可携带其个人信息。
 * 工单生命周期：SUBMITTED → REVIEWING → FULFILLED/REJECTED，法定 30 日响应（第 45 条）。
 * 详见 docs/DESIGN-P5-B57-CARD3-COMPLIANCE-2026-09-17.md §2。
 *
 * 注：customer_id 类型对齐 Customer.customerId（String(16)），非设计文档写的 BIGINT——
 * 设计文档 §2.1 写 BIGINT 是规格初稿，实证 Customer.customerId 是 String(16)，以代码为准订正。
 * type/status 的 CHECK 约束由 service 层白名单校验兜底（JPA ddl-auto=update 不生成 CHECK 约束）。
 */
@Entity
@Table(name = "dsar_request")
@Getter @Setter @NoArgsConstructor
public class DsarRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 工单号：DSAR+yyyyMMdd+三位序号（仿 LV/B28 既有范式，service 层取号防重）。 */
    @Column(name = "request_no", unique = true, length = 20)
    private String requestNo;

    /** 请求关联客户（FK→customer.customer_id，String(16) 对齐既有范式）。 */
    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** 请求类型：ACCESS / DELETE / RECTIFY / PORTABILITY（service 层白名单校验）。 */
    @Column(nullable = false, length = 16)
    private String type;

    /** 工单状态：SUBMITTED / REVIEWING / FULFILLED / REJECTED。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 客户填写的请求描述（≤500 字）。 */
    @Column(length = 500)
    private String description;

    /** 审核人工号（SUBMITTED/REVIEWING 态为空，FULFILLED/REJECTED 后写入）。 */
    @Column(length = 16)
    private String reviewer;

    /** 驳回原因（REJECTED 必填，FULFILLED 为空）。 */
    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    /** 请求时间（客户提交时间）。 */
    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    /** 法定截止时间（requested_at + 30 日，PIPL 第 45 条响应时限）。 */
    @Column(name = "deadline_at", nullable = false)
    private OffsetDateTime deadlineAt;

    /** 完成时间（FULFILLED 时写入）。 */
    @Column(name = "fulfilled_at")
    private OffsetDateTime fulfilledAt;

    /**
     * 履约数据（FULFILLED 时写入）：
     * - ACCESS：客户全量个人信息 JSON（Customer + Cards + Points + Tags）
     * - PORTABILITY：可携带数据 JSON（Customer 基础 + 消费记录 + 卡项余额）
     * - DELETE：匿名化结果 JSON（{"anonymizedAt":"...","note":"..."}，PIPL 第 73 条匿名化脱敏，不改 customer.status）
     * - RECTIFY：更正说明 JSON（{"fields":[...],"note":"..."}，实际字段修改由审核人在客户档案页操作）
     */
    @Column(name = "fulfillment_data", columnDefinition = "text")
    private String fulfillmentData;

    /** 记录创建时间。 */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (requestedAt == null) requestedAt = now;
        if (deadlineAt == null) deadlineAt = requestedAt.plusDays(30);
        if (status == null) status = "SUBMITTED";
        if (createdAt == null) createdAt = now;
    }
}
