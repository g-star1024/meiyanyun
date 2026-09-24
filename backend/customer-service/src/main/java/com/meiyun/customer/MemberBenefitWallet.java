package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 会员权益钱包（B93 次数型权益实例，本期仅 FREE_CARE 每月免费护理）。
 *
 * <p>懒发放：读模型/核销双路径 getOrCreate——当期无钱包且等级次数>0 则建
 * （total_times=发放时等级次数快照，level_snap=发放时等级）；UK(customer_id, period, benefit_type)
 * 为并发懒建兜底（撞行重读）。快照口径：月中调级不动当期已建钱包，次期按新等级发放。
 * 对账恒等式：Σ member_benefit_writeoff(status=OK) = wallet.used_times（按 customer+period）。
 */
@Entity
@Table(name = "member_benefit_wallet", uniqueConstraints = @UniqueConstraint(
        name = "uk_benefit_wallet", columnNames = {"customer_id", "period", "benefit_type"}))
@Getter @Setter @NoArgsConstructor
public class MemberBenefitWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wallet_id")
    private Long walletId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    /** 权益周期（YYYY-MM 自然月，北京时区口径）。 */
    @Column(name = "period", nullable = false, length = 7)
    private String period;

    /** 权益类型：本期常量 FREE_CARE（扩展预留）。 */
    @Column(name = "benefit_type", nullable = false, length = 16)
    private String benefitType;

    /** 发放时等级快照（月中调级不动当期）。 */
    @Column(name = "level_snap", nullable = false, length = 8)
    private String levelSnap;

    /** 当期配额（发放时等级次数快照）。 */
    @Column(name = "total_times", nullable = false)
    private Integer totalTimes;

    /** 已核销次数（行锁扣次，与 OK 流水对账）。 */
    @Column(name = "used_times", nullable = false)
    private Integer usedTimes;

    /** 乐观锁（行锁扣次双保险）。 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (totalTimes == null) totalTimes = 0;
        if (usedTimes == null) usedTimes = 0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
