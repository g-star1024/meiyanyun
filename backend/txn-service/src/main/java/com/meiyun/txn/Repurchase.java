package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * M4-18 复购回访：复购 / 资产转移。
 * 红线：三方双签（客户确认 + 经办 + 店长）+ 知情同意书（consentAck 未签不得进入签核）。
 */
@Entity
@Table(name = "repurchase")
@Getter @Setter @NoArgsConstructor
public class Repurchase {

    @Id
    @Column(name = "repurchase_no", length = 24)
    private String repurchaseNo;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "biz_type", nullable = false, length = 8)
    private String bizType;            // 复购 | 资产转移

    @Column(name = "target_project", length = 64)
    private String targetProject;

    @Column(name = "from_card_no", length = 24)
    private String fromCardNo;

    @Column(name = "to_card_no", length = 24)
    private String toCardNo;

    /** B85 D3：接收客户编号（资产转移单必填，允许跨客户；复购单为空）。 */
    @Column(name = "to_customer_id", length = 16)
    private String toCustomerId;

    @Column(name = "transfer_times")
    private Integer transferTimes;

    @Column(name = "transfer_amount")
    private Long transferAmount;

    /** B85 D2：随转赠金（分，≥0 默认 0，上限=来源卡 gift_balance 超 422）。 */
    @Column(name = "transfer_gift")
    private Long transferGift;

    /** B85 D6：关联合同号（可空；卡3 合同实体建成后联动校验：存在＋生效中＋客户匹配）。 */
    @Column(name = "contract_no", length = 24)
    private String contractNo;

    @Column(name = "consent_ack", nullable = false)
    private boolean consentAck;        // 知情同意书已签（硬前置）

    @Column(name = "consent_text", length = 256)
    private String consentText;

    @Column(nullable = false, length = 8)
    private String status = "待签核";

    @Column(length = 32)
    private String sign1;
    @Column(name = "sign1_role", length = 32)
    private String sign1Role;
    @Column(name = "signed_at1")
    private OffsetDateTime signedAt1;

    @Column(length = 32)
    private String sign2;
    @Column(name = "sign2_role", length = 32)
    private String sign2Role;
    @Column(name = "signed_at2")
    private OffsetDateTime signedAt2;

    @Column(length = 32)
    private String sign3;
    @Column(name = "sign3_role", length = 32)
    private String sign3Role;
    @Column(name = "signed_at3")
    private OffsetDateTime signedAt3;

    @Column(length = 256)
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
