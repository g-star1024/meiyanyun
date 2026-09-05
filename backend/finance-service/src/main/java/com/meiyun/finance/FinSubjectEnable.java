package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * fin_subject_enable 会计科目启用表（B5，JPA ddl-auto）。
 *
 * <p>科目码与 financeCore SUBJECT_LABEL 对齐（RF-CASH/RF-BANK/RF-RECEIVABLE/RF-DEPOSIT/
 * RF-REVENUE/RF-REFUND/TK-MATERIAL/TK-COST/TK-DEPRECIATION/TK-LOSS/TK-LABOR）；
 * RF-RECEIVABLE（应收账款）默认停用，其余默认启用。仅控制展示与记账口径，不触达资金。
 */
@Entity
@Table(name = "fin_subject_enable")
@Getter @Setter @NoArgsConstructor
public class FinSubjectEnable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "enable_id")
    private Long enableId;

    @Column(nullable = false, unique = true, length = 16)
    private String code;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
