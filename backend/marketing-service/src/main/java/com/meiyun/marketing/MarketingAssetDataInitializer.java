package com.meiyun.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 素材库启动播种（M5-13）：表为空时幂等灌入 10 条演示素材（对齐前端活规格）。
 * tags/storeCodes 存 JSON 文本数组；scope=ALL 时 storeCodes 存空数组（前端按门店主数据展开）；
 * 指定门店授权落 SST 编码（仅营业中门店）；日期相对今天，保证有效期演示不过期。
 */
@Component
@Order(30)
public class MarketingAssetDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MarketingAssetDataInitializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MarketingAssetRepository repo;

    public MarketingAssetDataInitializer(MarketingAssetRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repo.count() > 0) {
            log.info("素材库已有 {} 条，跳过播种", repo.count());
            return;
        }
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();

        int seq = 0;
        seq = save(seq, "暑期水光主海报", "IMAGE", List.of("暑期", "水光", "促销"), "ALL", List.of(),
                today.plusDays(30), 12, "brand", null, now);
        seq = save(seq, "新客88元体验海报", "IMAGE", List.of("新客", "体验"), "SPECIFIED", List.of("SST01", "SST02"),
                today.plusDays(90), 8, "teal", null, now);
        seq = save(seq, "热玛吉种草短视频", "VIDEO", List.of("热玛吉", "种草", "抗衰"), "ALL", List.of(),
                today.plusDays(60), 6, "purple", null, now);
        seq = save(seq, "光子嫩肤对比视频", "VIDEO", List.of("光子", "效果"), "ALL", List.of(),
                today.plusDays(90), 4, "orange", null, now);
        seq = save(seq, "双11狂欢文案", "COPY", List.of("双11", "促销", "文案"), "ALL", List.of(),
                today.plusDays(20), 15, "gold",
                "双11 礼遇焕新，爆款项目限时直降，会员再享折上折！", now);
        seq = save(seq, "新客体验邀约话术", "COPY", List.of("新客", "邀约", "文案"), "ALL", List.of(),
                today.plusDays(90), 9, "blue",
                "亲爱的，新客专享88元体验套餐已为您准备好，到店即赠皮肤检测一次。", now);
        seq = save(seq, "品牌Logo-横版", "LOGO", List.of("Logo", "品牌"), "ALL", List.of(),
                today.plusDays(365), 24, "brand", null, now);
        seq = save(seq, "品牌Logo-竖版", "LOGO", List.of("Logo", "品牌"), "ALL", List.of(),
                today.plusDays(365), 18, "teal", null, now);
        seq = save(seq, "会员日活动海报", "IMAGE", List.of("会员日", "促销"), "SPECIFIED", List.of("SST03", "SST04"),
                today.plusDays(10), 7, "gold", null, now);
        seq = save(seq, "门店环境探店视频", "VIDEO", List.of("探店", "环境"), "ALL", List.of(),
                today.plusDays(90), 3, "teal", null, now);

        log.info("素材库播种完成：演示素材 {} 条", seq);
    }

    private int save(int seq, String name, String type, List<String> tags, String scope, List<String> storeCodes,
                     LocalDate expireAt, int refCount, String accent, String content, OffsetDateTime now) {
        MarketingAsset a = new MarketingAsset();
        a.setAssetId("AS-SEED-" + String.format("%03d", seq + 1));
        a.setAssetName(name);
        a.setType(type);
        a.setTags(json(tags));
        a.setScope(scope);
        a.setStoreCodes(json(storeCodes));
        a.setExpireAt(expireAt);
        a.setRefCount(refCount);
        a.setAccent(accent);
        a.setContent(content);
        a.setCreatedAt(now.minusDays(refCount % 30));
        repo.save(a);
        return seq + 1;
    }

    private static String json(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
