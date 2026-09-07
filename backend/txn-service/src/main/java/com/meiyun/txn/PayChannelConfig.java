package com.meiyun.txn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 支付渠道配置（pay_channel_config，B12）。
 *
 * <p>渠道随支付方式走：wxpay 微信支付 / alipay 支付宝 / transfer 银行转账需要对接参数；
 * 现金 cash、储值余额 balance 为系统内置渠道，无需配置（列表接口返回只读内置行）。
 *
 * <p>密钥红线：{@code api_v3_key}（支付宝/微信 APIv3 密钥）<b>写后不可读回</b>——
 * 列表/详情一律不下发明文，仅以 hasApiKey 标记是否已设置；审计日志不记明文。
 * store_code 空串 = 集团默认模板（唯一约束渠道+门店，门店列空串而非 NULL）。
 */
@Entity
@Table(name = "pay_channel_config",
        uniqueConstraints = @UniqueConstraint(name = "uk_pay_channel_config",
                columnNames = {"channel_code", "store_code"}))
@Getter @Setter @NoArgsConstructor
public class PayChannelConfig {

    /** 配置号：PCC + yyyyMMdd + - + 6 位序号。 */
    @Id
    @Column(name = "config_id", length = 24)
    private String configId;

    /** 渠道码：wxpay/alipay/transfer。 */
    @Column(name = "channel_code", nullable = false, length = 16)
    private String channelCode;

    /** 渠道名称：微信支付/支付宝/银行转账。 */
    @Column(name = "channel_name", nullable = false, length = 32)
    private String channelName;

    /** 门店码；空串 = 集团默认模板。 */
    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 启用/停用（随时可调整，停用后收银台不引导走该渠道但不影响历史账）。 */
    @Column(nullable = false)
    private boolean enabled;

    /** 应用 AppID（微信/支付宝开放平台）。 */
    @Column(name = "app_id", length = 64)
    private String appId;

    /** 商户号。 */
    @Column(name = "mch_id", length = 64)
    private String mchId;

    /** APIv3 密钥（写后不可读回；仅写接口接收，列表不回显）。 */
    @Column(name = "api_v3_key", length = 128)
    private String apiV3Key;

    /** 证书序列号。 */
    @Column(name = "cert_serial", length = 128)
    private String certSerial;

    /** 支付回调地址。 */
    @Column(name = "notify_url", length = 256)
    private String notifyUrl;

    /** 对账方式：IMPORT 手动导入账单（本期唯一）/ API 自动拉取（预留，本期不实现）。 */
    @Column(name = "reconcile_mode", nullable = false, length = 16)
    private String reconcileMode;

    /** 渠道手续费率（万分位，如 60 = 0.6%）；勾兑时估算手续费参考，不自动入账。 */
    @Column(name = "fee_rate", nullable = false)
    private int feeRate;

    @Column(length = 256)
    private String remark;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (storeCode == null) storeCode = "";
        if (reconcileMode == null) reconcileMode = "IMPORT";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
