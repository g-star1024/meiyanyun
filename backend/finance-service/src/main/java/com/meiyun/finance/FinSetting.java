package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * fin_setting 财务设置单例（B5，JPA ddl-auto，setting_id 恒为 1）。
 *
 * <p>仅配置税率、结算周期、对账与镜像参数；镜像源（金蝶/用友）单向同步，绝不反向写资金池。
 * 税率 BigDecimal(5,4) 存 0~1 小数；diff_threshold 为 Long「分」（前端「元」）；
 * 行不存在时读端点回落内置默认值（vat 0.06 / surtax 0.12 / income 0.25 / 结算 5 号 /
 * 提成 10 号 / T+1 / 阈值 100 元 / 金蝶开 / 用友关 / 重试 3）。
 */
@Entity
@Table(name = "fin_setting")
@Getter @Setter @NoArgsConstructor
public class FinSetting {

    @Id
    @Column(name = "setting_id")
    private Long settingId;

    @Column(name = "vat_rate", precision = 5, scale = 4)
    private BigDecimal vatRate;

    @Column(name = "surtax_rate", precision = 5, scale = 4)
    private BigDecimal surtaxRate;

    @Column(name = "income_tax_rate", precision = 5, scale = 4)
    private BigDecimal incomeTaxRate;

    @Column(name = "settle_day")
    private Integer settleDay;

    @Column(name = "commission_pay_day")
    private Integer commissionPayDay;

    @Column(name = "reconcile_tn")
    private Integer reconcileTn;

    /** 对账差异阈值（分；超过需双签调平）。 */
    @Column(name = "diff_threshold")
    private Long diffThreshold;

    @Column(name = "mirror_kingdee")
    private Boolean mirrorKingdee;

    @Column(name = "mirror_yonyou")
    private Boolean mirrorYonyou;

    @Column(name = "outbox_retry")
    private Integer outboxRetry;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;
}
