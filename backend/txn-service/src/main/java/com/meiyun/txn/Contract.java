package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * P5-B85 卡3 合同实体（txn 域，ddl-auto=update 自动建表零 Flyway）：
 * 课程 / 储值 / 套餐 / 服务四类合同的签署快照与生命周期（草稿→生效中→已履行|已终止）。
 * ordersJson / assetsJson 为 JSON 数组字符串 TEXT 落库（同 ApprovalTodo 口径）。
 * D6 联动：RepurchaseService.create() 填 contractNo 时校验存在＋生效中＋客户匹配转出或接收方。
 */
@Entity
@Table(name = "contract")
@Getter @Setter @NoArgsConstructor
public class Contract {

    @Id
    @Column(name = "contract_no", length = 24)
    private String contractNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** COURSE 课程 | STORED_VALUE 储值 | PACKAGE 套餐 | SERVICE 服务 */
    @Column(name = "contract_type", nullable = false, length = 16)
    private String contractType;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(name = "sign_date", nullable = false)
    private LocalDate signDate;

    /** 合同总额（分） */
    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    /** 签署订单快照 JSON 数组字符串：[{orderNo,amount,itemName}] */
    @Column(name = "orders_json", columnDefinition = "TEXT")
    private String ordersJson;

    /** 关联卡资产快照 JSON 数组字符串：[{cardNo,cardItem}] */
    @Column(name = "assets_json", columnDefinition = "TEXT")
    private String assetsJson;

    /** 冷静期天数（默认 7） */
    @Column(name = "cooling_days", nullable = false)
    private Integer coolingDays = 7;

    /** 违约金率（基点万分比，2000=20%） */
    @Column(name = "penalty_rate", nullable = false)
    private Integer penaltyRate = 2000;

    @Column(name = "refund_terms", length = 512)
    private String refundTerms;

    @Column(length = 256)
    private String remarks;

    @Column(name = "signed_by", length = 32)
    private String signedBy;

    /** 草稿 | 生效中 | 已履行 | 已终止（四值由 ContractService 状态机保证） */
    @Column(nullable = false, length = 8)
    private String status = "草稿";

    @Column(name = "effective_at")
    private OffsetDateTime effectiveAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "terminated_at")
    private OffsetDateTime terminatedAt;

    @Column(name = "terminate_reason", length = 256)
    private String terminateReason;

    /** 棒⑧卡3 电子签接入位：厂商签署流程号（发起签署成功回填，回调核验一致性防串号） */
    @Column(name = "esign_flow_id", length = 64)
    private String esignFlowId;

    /** 电子签状态：NONE 未发起 | SENT 待客户签署 | SIGNED 已签署 | DECLINED 已拒签 | FAILED 签署失败 */
    @Column(name = "esign_status", nullable = false, length = 16)
    private String esignStatus = "NONE";

    /** 客户签署人姓名（签署回调回填） */
    @Column(name = "esign_signer_name", length = 32)
    private String esignSignerName;

    /** 客户签名图/厂商签署回执（TEXT 不透明快照，同 ordersJson 口径） */
    @Column(name = "esign_signature", columnDefinition = "TEXT")
    private String esignSignature;

    @Column(name = "esign_sent_at")
    private OffsetDateTime esignSentAt;

    @Column(name = "esign_signed_at")
    private OffsetDateTime esignSignedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
