package com.meiyun.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 进项发票登记簿（B63 卡4 L86，V39 fin_input_invoice）。
 *
 * <p>进项台账独立于销项 fin_invoice（字段语义与状态机不同，不混表）。扣税凭证五类
 * （增值税法实施条例 §11）：SPECIAL 专票 / CUSTOMS 海关缴款书 / TOLL 通行费电子普票 /
 * PASSENGER 旅客运输 / OTHER 其他。
 *
 * <p>状态机：UNCONFIRMED 待确认 → CONFIRMED 已用途确认 → DEDUCTED 已抵扣 →
 * TRANSFERRED_OUT 已进项转出；UNCONFIRMED 可直接走 NON_DEDUCTIBLE 不抵扣终态旁路。
 * 用途确认无法定期限硬卡（国税总局公告 2019 年第 45 号取消 360 日），列表仅票龄软提示。
 *
 * <p>金额三列 Long「分」，落库恒等式 amount = netAmount + taxAmount（DB CHECK 兜底）；
 * 净额/税额由服务端复用 FinConfigService.computeTaxFen 价税分离，不信任客户端。
 */
@Entity
@Table(name = "fin_input_invoice")
@Getter
@Setter
@NoArgsConstructor
public class FinInputInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 内部登记号 PINV-yyyyMMdd-0001（独立序列，synchronized 取号）。 */
    @Column(name = "register_no", nullable = false, unique = true, length = 24)
    private String registerNo;

    /** 发票代码（专票有代码；电子/海关票据可空）。 */
    @Column(name = "invoice_code", length = 32)
    private String invoiceCode;

    /** 发票号码（外部税局号码，原样登记）。 */
    @Column(name = "invoice_no", nullable = false, length = 32)
    private String invoiceNo;

    /** SPECIAL / CUSTOMS / TOLL / PASSENGER / OTHER。 */
    @Column(name = "invoice_kind", nullable = false, length = 16)
    private String invoiceKind;

    /** 开票方名称（录入即活，冗余不跨服务关联）。 */
    @Column(name = "seller_name", nullable = false, length = 128)
    private String sellerName;

    /** 开票方纳税人识别号（无税号场景以证件号占位）。 */
    @Column(name = "seller_tax_no", nullable = false, length = 32)
    private String sellerTaxNo;

    /** 可选软关联 store-service supplier.id（无物理外键，跨服务仅联想辅助）。 */
    @Column(name = "supplier_id")
    private Long supplierId;

    /** 价税合计（分）。 */
    @Column(nullable = false)
    private Long amount;

    /** 不含税净额（分，服务端 HALF_UP 价税分离）。 */
    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    /** 税额（分）。 */
    @Column(name = "tax_amount", nullable = false)
    private Long taxAmount;

    /** 税率五档：0 / 0.01 / 0.03 / 0.06 / 0.13。 */
    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    /** 采购用途沿用销项三档：SERVICE / PRODUCT / MEMBERSHIP。 */
    @Column(nullable = false, length = 16)
    private String category;

    /** PENDING 待确认 / DEDUCT 抵扣 / NO_DEDUCT 不抵扣 / REFUND 退税（远期）。 */
    @Column(nullable = false, length = 16)
    private String purpose = "PENDING";

    /** UNCONFIRMED / CONFIRMED / DEDUCTED / TRANSFERRED_OUT / NON_DEDUCTIBLE。 */
    @Column(nullable = false, length = 20)
    private String status = "UNCONFIRMED";

    /** 不抵扣/转出七码：WELFARE/LOSS_GOODS/LOSS_PRODUCT/LOSS_REAL_ESTATE/LOSS_CONSTRUCTION/LOAN_DAILY/OTHER。 */
    @Column(name = "nondeduct_reason", length = 16)
    private String nondeductReason;

    /** 进项转出额（分）。 */
    @Column(name = "transfer_out_amount", nullable = false)
    private Long transferOutAmount = 0L;

    /** 抵扣归属申报期（软关联 fin_tax_period.id，无物理外键）。 */
    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "deducted_at")
    private OffsetDateTime deductedAt;

    @Column(name = "transferred_at")
    private OffsetDateTime transferredAt;

    /** 数据域（DataScope.storeSpec 强制）。 */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(nullable = false, length = 64)
    private String operator;

    /** 用途确认人（勾选动作留痕）。 */
    @Column(length = 64)
    private String confirmer;

    @Column(length = 256)
    private String remark;

    /** 登记幂等键（客户端生成 UUID，重复登记回 409 中文）。 */
    @Column(name = "idem_key", nullable = false, unique = true, length = 80)
    private String idemKey;

    @Column(name = "created_by", length = 16, nullable = false)
    private String createdBy = "system";

    @Column(name = "updated_by", length = 16, nullable = false)
    private String updatedBy = "system";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
