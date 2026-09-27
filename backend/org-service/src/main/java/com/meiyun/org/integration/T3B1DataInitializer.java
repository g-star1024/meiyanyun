package com.meiyun.org.integration;

import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T3-B1 三方连接器种子（@Order(48)，customer 侧 T2-B3 用 47 顺链，org 侧无既有 @Order）。
 * 仅 meiyun_seed 库生效（JDBC URL 门控）；connectorRepository.count()>0 跳过幂等（一体事务单门控）。
 * 9 条连接器逐字锚定前端 mock（stores/t3Integration.ts seed() L320-328）的
 * type/name/endpoint/credentialKey 四要素；code 取 DESIGN-T3 §三种子九码。
 * 红线②：状态一律 DISCONNECTED 诚实态（mock 中 CONNECTED/ERROR 假态不抄）——
 * CONNECTED 只能由真实探测驱动，种子不得伪造连通。
 */
@Component
@Order(48)
public class T3B1DataInitializer implements ApplicationRunner {

    private final IntegrationConnectorRepository connectorRepository;
    private final String datasourceUrl;

    public T3B1DataInitializer(IntegrationConnectorRepository connectorRepository,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.connectorRepository = connectorRepository;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        if (connectorRepository.count() > 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();

        seed(now, "CONN-WX", IntegrationConnector.TYPE_PAYMENT, "微信支付",
                "https://api.mch.weixin.qq.com/v3", "wx_mch_****a3f2");
        seed(now, "CONN-ALI", IntegrationConnector.TYPE_PAYMENT, "支付宝",
                "https://openapi.alipay.com/gateway", "ali_pid_****b8c1");
        seed(now, "CONN-UMS", IntegrationConnector.TYPE_PAYMENT, "银联刷卡",
                "https://api.chinaums.com", "ums_mer_****d4e5");
        seed(now, "CONN-SHYB", IntegrationConnector.TYPE_INSURANCE, "上海医保接口",
                "https://ybj.sh.gov.cn/api", "sh_yb_****f6a7");
        seed(now, "CONN-WECOM", IntegrationConnector.TYPE_WECOM, "企业微信",
                "https://qyapi.weixin.qq.com/cgi-bin", "ww_corp_****g9b3");
        seed(now, "CONN-BW", IntegrationConnector.TYPE_TAX, "百望税控",
                "https://api.baiwang.com/invoice", "bw_tax_****h2c4");
        seed(now, "CONN-OCEAN", IntegrationConnector.TYPE_ADS, "巨量引擎",
                "https://ad.oceanengine.com/open_api", "ocean_****j5d6");
        seed(now, "CONN-KD", IntegrationConnector.TYPE_KINGDEE, "金蝶云星空 ERP",
                "https://api.kingdee.com/koas", "kd_app_****k7e8");
        seed(now, "CONN-YY", IntegrationConnector.TYPE_YONYOU, "用友 U8 ERP",
                "https://api.yonyoucloud.com/u8", "yy_app_****l9f0");
    }

    /** 单条连接器种子：status 恒 DISCONNECTED（诚实态），lastSyncAt/lastError 留空。 */
    private void seed(OffsetDateTime now, String code, String type, String name,
                      String endpoint, String credentialKey) {
        IntegrationConnector c = new IntegrationConnector();
        c.setCode(code);
        c.setType(type);
        c.setName(name);
        c.setEndpoint(endpoint);
        c.setCredentialKey(credentialKey);
        c.setStatus(IntegrationConnector.STATUS_DISCONNECTED);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        connectorRepository.save(c);
    }
}
