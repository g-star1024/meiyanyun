package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * fin_invoice 发票登记（B5 发票管理业务表，JPA ddl-auto）。
 *
 * <p>发票仅作业务凭证登记：开票动作由外部开票系统完成，本表登记抬头、税额、关联订单、状态，
 * 不直接触达资金池。状态机：DRAFT 待开票 → ISSUED 已开票 → VOIDED 当月作废 / RED_FLUSHED 跨月红冲；
 * 开具/作废需 finance:invoice:edit，红冲需 finance:invoice:approve（双签）。
 * 金额 Long「分」（amount 价税合计、tax_amount 税额）；order_refs 为逗号分隔关联单号；
 * store_code 关联门店（读时经 store 服务 name-map 解析中文名）。
 */
@Entity
@Table(name = "fin_invoice")
@Getter @Setter @NoArgsConstructor
public class FinInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "invoice_no", nullable = false, unique = true, length = 24)
    private String invoiceNo;

    /** 票种：NORMAL 普票 / SPECIAL 专票 / ELECTRONIC 电子普票。 */
    @Column(nullable = false, length = 16)
    private String type;

    /** 项目类别：SERVICE 医疗服务 / PRODUCT 产品销售 / MEMBERSHIP 会员卡疗程。 */
    @Column(nullable = false, length = 16)
    private String category;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(name = "tax_no", length = 32)
    private String taxNo;

    /** 价税合计（分，始终为正）。 */
    @Column(nullable = false)
    private Long amount;

    /** 税额（分）：服务端按 amount 与 taxRate 计算，不信任客户端。 */
    @Column(name = "tax_amount", nullable = false)
    private Long taxAmount;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "buyer_name", nullable = false, length = 64)
    private String buyerName;

    /** 关联订单号，逗号分隔。 */
    @Column(name = "order_refs", length = 512)
    private String orderRefs;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 状态：DRAFT / ISSUED / VOIDED / RED_FLUSHED。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 64)
    private String operator;

    @Column(length = 64)
    private String reviewer;

    @Column(length = 256)
    private String remark;

    /** 幂等键（可选）：重复创建回带已存在发票，不双建。 */
    @Column(name = "idem_key", unique = true, length = 80)
    private String idemKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** 开票/草稿时间（列表按此倒序；开具时刷新为实际开具时间）。 */
    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;
}
