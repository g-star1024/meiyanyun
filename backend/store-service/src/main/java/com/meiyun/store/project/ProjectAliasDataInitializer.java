package com.meiyun.store.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目别名种子（P5-B96 卡1，DESIGN-T2 §3 D2 / §6）：种子库 order_item 泛化名 Top12
 * （实测覆盖 1108/1111 ≈ 99.7%，验收线 ≥90%）→ SKU 全局别名映射。
 *
 * <p>仅种子库播种（JDBC URL 含 meiyun_seed，B40 门控，同 StoreMasterDataInitializer 约定）；
 * 正式栈别名由运营在页面 CRUD 维护。复用 {@link ProjectAliasService#seedAlias}（不写审计）；
 * count 门控幂等。sku 播种前逐个 findBySku 校验存在，缺失 log.warn 跳过（环境差异防御）。
 * Order(77) 晚于项目目录种子 Order(70)（StorePriceCatalogDataInitializer 播 15 SKU）。
 *
 * <p>未覆盖的长尾泛化名（如「热玛吉眼周」「水光针全脸」，各 1 行）留作 CRUD 补录演示素材
 * （DESIGN-T2 §6 未命中清单运营补录路径）。
 */
@Component
@Order(77)
public class ProjectAliasDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectAliasDataInitializer.class);

    private static final String OP = "system";

    /** 别名规格：泛化项目名/指向 SKU/备注 */
    private record AliasSpec(String alias, String sku, String remark) {}

    private final ProjectAliasRepository aliasRepo;
    private final ProductSkuRepository skuRepo;
    private final ProjectAliasService aliasService;
    private final String datasourceUrl;

    public ProjectAliasDataInitializer(ProjectAliasRepository aliasRepo,
                                       ProductSkuRepository skuRepo,
                                       ProjectAliasService aliasService,
                                       @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.aliasRepo = aliasRepo;
        this.skuRepo = skuRepo;
        this.aliasService = aliasService;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过项目别名演示数据播种；正式栈别名由运营页面 CRUD 维护",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (aliasRepo.count() > 0) {
            log.info("项目别名已存在（{} 条），跳过播种", aliasRepo.count());
            return;
        }
        List<AliasSpec> specs = List.of(
                new AliasSpec("光子嫩肤", "LUM-M22", "泛化名归桶：M22 光子嫩肤"),
                new AliasSpec("超声炮局部", "PEN-ULTRA-PRO", "泛化名归桶：半岛超声炮"),
                new AliasSpec("射频紧致", "ZH-THERMAGE-FL", "泛化名归桶：热玛吉射频"),
                new AliasSpec("热玛吉全面部", "ZH-THERMAGE-FL", "泛化名归桶：热玛吉射频"),
                new AliasSpec("皮秒祛斑", "ZH-PICOWAY", "泛化名归桶：超皮秒"),
                new AliasSpec("激光祛痘", "ZH-PICOWAY", "泛化名归桶：超皮秒"),
                new AliasSpec("玻尿酸填充", "AGN-JUV-1ML", "泛化名归桶：乔雅登玻尿酸"),
                new AliasSpec("肉毒素除皱", "AGN-BTX-100", "泛化名归桶：保妥适瘦脸针"),
                new AliasSpec("水光针单次", "HX-AQUA-BASE", "泛化名归桶：基础水光针"),
                new AliasSpec("果酸焕肤", "HX-QUADHA", "泛化名归桶：润百颜次抛精华"),
                new AliasSpec("小气泡清洁", "HX-BUBBLE", "泛化名归桶：小气泡深层清洁"),
                new AliasSpec("敏感肌修复单次", "HX-QUADHA", "泛化名归桶：润百颜次抛精华"));
        int seeded = 0;
        for (AliasSpec spec : specs) {
            if (skuRepo.findBySku(spec.sku()).isEmpty()) {
                log.warn("项目别名「{}」指向 SKU {} 不存在，跳过（环境差异防御）", spec.alias(), spec.sku());
                continue;
            }
            aliasService.seedAlias(spec.alias(), null, spec.sku(), spec.remark(), OP);
            seeded++;
        }
        log.info("项目别名播种完成：{} 条全局别名（order_item 泛化名 Top12 归桶 SKU service_category）", seeded);
    }
}
