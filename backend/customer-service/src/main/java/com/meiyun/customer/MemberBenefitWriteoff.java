package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会员权益核销流水（B93，append-only：每次核销动作落一行，含异常态）。
 *
 * <p>status：OK 正常扣次 / NO_WALLET 当期无钱包（等级无此权益）/ EXHAUSTED 次数已用完——
 * 异常不抛 400，落流水 ok=false 返回（仿 CouponWriteoffService.saveAbnormal 范式），
 * 仅参数非法（项目名空/超长、clientRequestId 非 UUID、客户已合并）中文 400。
 * client_request_id UK 为前端 UUID 幂等键：重复提交/双击/重放命中直接返既有流水，不重复扣次。
 */
@Entity
@Table(name = "member_benefit_writeoff", uniqueConstraints = {
        @UniqueConstraint(name = "uk_benefit_writeoff_no", columnNames = {"writeoff_no"}),
        @UniqueConstraint(name = "uk_benefit_writeoff_req", columnNames = {"client_request_id"})})
@Getter @Setter @NoArgsConstructor
public class MemberBenefitWriteoff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "writeoff_id")
    private Long writeoffId;

    /** 核销单号：BW+yyyyMMdd-6 位序号（仿卡台账 RC/MC 单号口径，synchronized+库内当日最大号递增）。 */
    @Column(name = "writeoff_no", nullable = false, length = 20)
    private String writeoffNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** 核销所属周期（YYYY-MM）。 */
    @Column(name = "period", nullable = false, length = 7)
    private String period;

    /** 权益类型：FREE_CARE。 */
    @Column(name = "benefit_type", nullable = false, length = 16)
    private String benefitType;

    /** 核销时钱包等级快照。 */
    @Column(name = "level_snap", nullable = false, length = 8)
    private String levelSnap;

    /** 手输护理项目名（≤40 字必填）。 */
    @Column(name = "project_name", nullable = false, length = 40)
    private String projectName;

    /** OK / NO_WALLET / EXHAUSTED。 */
    @Column(name = "status", nullable = false, length = 12)
    private String status;

    /** 异常中文原因（OK 为 null）。 */
    @Column(name = "reason", length = 80)
    private String reason;

    /** 前端 UUID 幂等键（重复提交返既有流水）。 */
    @Column(name = "client_request_id", nullable = false, length = 64)
    private String clientRequestId;

    /** 操作人（DataScope.currentActor()，请求体 operator 不可信）。 */
    @Column(name = "operator", nullable = false, length = 16)
    private String operator;

    /** 核销门店（D10：DataScope 当前门店，缺省落客户归属门店）。 */
    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "store_name", length = 32)
    private String storeName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
