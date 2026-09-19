package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * fin_abnormal_bill 异常账务处置单（B63 卡1 L84，Flyway V37 建表）。
 *
 * <p>长短款/错账登记 → 同步提 txn 审批（bizType=FIN_ADJUSTMENT）→ 终审回调
 * APPROVED/REJECTED → APPROVED 单再 dispose 落 ADJUST 资金分录 → DISPOSED。
 * 金额全部 Long「分」；人工处置禁直接写 MATERIAL/LOSS 分录，动账只走终审后 ADJUST。
 */
@Entity
@Table(name = "fin_abnormal_bill")
@Getter @Setter @NoArgsConstructor
public class FinAbnormalBill {

    /** AB+yyyyMMdd+'-'+6位序号（北京日）。 */
    @Id
    @Column(name = "bill_no", length = 24)
    private String billNo;

    /** 登记幂等键（前端连点/重试去重，可空）。 */
    @Column(name = "idem_key", length = 64)
    private String idemKey;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** SHORT 短款 / LONG 长款 / WRONG 错账。 */
    @Column(nullable = false, length = 8)
    private String type;

    /** WRONG 登记必填 IN/OUT；SHORT/LONG 为空（处置时由类型推导）。 */
    @Column(length = 4)
    private String direction;

    @Column(name = "amount_fen", nullable = false)
    private Long amountFen;

    /** MANUAL 手工登记 / RECONCILE 对账差异转入。 */
    @Column(nullable = false, length = 8)
    private String source = "MANUAL";

    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(nullable = false, length = 256)
    private String reason;

    /** PENDING_APPROVAL / APPROVED / REJECTED / DISPOSED。 */
    @Column(nullable = false, length = 16)
    private String status = "PENDING_APPROVAL";

    @Column(name = "approval_no", length = 32)
    private String approvalNo;

    @Column(name = "dispose_fund_entry_id")
    private Long disposeFundEntryId;

    @Column(name = "created_by", nullable = false, length = 16)
    private String createdBy;

    @Column(length = 16)
    private String reviewer;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "disposed_at")
    private OffsetDateTime disposedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
