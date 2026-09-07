package com.meiyun.txn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * B12 支付渠道配置演示种子（仅 meiyun_seed 独立测试库播种，prod 库跳过）。
 *
 * <p>集团默认模板（store_code 空串）：微信支付 / 支付宝 enabled（测试参数、密钥占位非真实凭证），
 * 银行转账 disabled（待启用演示）。幂等：表非空则跳过，沿用 B9/B10/B11 DataInitializer 范式。
 */
@Component
@Order(60)
public class PayChannelDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PayChannelDataInitializer.class);

    private final PayChannelConfigRepository repo;
    private final String datasourceUrl;

    public PayChannelDataInitializer(PayChannelConfigRepository repo,
                                     @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.repo = repo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("[PayChannelSeed] 非 meiyun_seed 库，跳过支付渠道配置播种。");
            return;
        }
        if (repo.count() > 0) {
            log.info("[PayChannelSeed] pay_channel_config 已有 {} 行，跳过播种。", repo.count());
            return;
        }
        seed("PCC-SEED-001", "wxpay", "微信支付", true,
                "wx-test-appid-0001", "1900000001", "wxapiv3-test-placeholder-key-0001",
                "wx-cert-serial-0001", "https://pay.example.com/wx/notify", 60,
                "种子测试参数，非真实商户凭证");
        seed("PCC-SEED-002", "alipay", "支付宝", true,
                "ali-test-appid-0002", "2088000000200002", "ali-apiv3-test-placeholder-key-0002",
                "ali-cert-serial-0002", "https://pay.example.com/ali/notify", 55,
                "种子测试参数，非真实商户凭证");
        seed("PCC-SEED-003", "transfer", "银行转账", false,
                null, null, null, null, null, 0,
                "银行转账对账参数待配置（默认停用演示）");
        log.info("[PayChannelSeed] 已播种 3 行渠道配置（wxpay/alipay enabled，transfer disabled）。");
    }

    private void seed(String configId, String channel, String name, boolean enabled,
                      String appId, String mchId, String apiV3Key, String certSerial,
                      String notifyUrl, int feeRate, String remark) {
        PayChannelConfig c = new PayChannelConfig();
        c.setConfigId(configId);
        c.setChannelCode(channel);
        c.setChannelName(name);
        c.setStoreCode("");
        c.setEnabled(enabled);
        c.setAppId(appId);
        c.setMchId(mchId);
        c.setApiV3Key(apiV3Key);
        c.setCertSerial(certSerial);
        c.setNotifyUrl(notifyUrl);
        c.setReconcileMode("IMPORT");
        c.setFeeRate(feeRate);
        c.setRemark(remark);
        c.setCreatedBy("system");
        c.setUpdatedBy("system");
        repo.save(c);
    }
}
