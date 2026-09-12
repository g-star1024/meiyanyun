package com.meiyun.store.pricelist;

import com.meiyun.store.project.ProductBrand;
import com.meiyun.store.project.ProductBrandRepository;
import com.meiyun.store.project.ProductCategory;
import com.meiyun.store.project.ProductCategoryRepository;
import com.meiyun.store.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目目录 + 门店价目主数据种子（B14，DESIGN-P5 §五）：
 * 9 品牌 / 16 品类（二级树）/ 15 项目 SKU / SST01 门店价目 11 条（8 ACTIVE / 2 PENDING / 1 DISABLED）。
 *
 * <p>品牌/品类/SKU 集团目录全环境播种（两栈都需要，同 B13 惯例）；SST01 门店价目仅种子库播种
 * （JDBC URL 含 meiyun_seed，B40 门控，同 CouponWriteoffDataInitializer 约定）。复用
 * {@link ProjectService}/{@link PricelistService} 的 seed* 辅助方法（不写审计）；count 门控幂等。两条 PENDING 价目 requested_by 播 SST01 店长「许店长」。
 * 金额「元→分」×100；Order(70) 晚于房间床位/设备种子 Order(60)。
 */
@Component
@Order(70)
public class StorePriceCatalogDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorePriceCatalogDataInitializer.class);

    private static final String STORE = "SST01";
    private static final String OP = "system";
    private static final String MANAGER = "许店长";

    /** 品牌规格：编码/名称/简称/产地/供应商/状态/头像色/备注 */
    private record BrandSpec(String code, String name, String shortName, String origin, String supplier,
                             String status, String logoColor, String remark) {}

    /** 品类规格：编码/名称/品牌序号(0起)/父品类编码(null=一级)/排序/备注 */
    private record CatSpec(String code, String name, int brandIdx, String parentCode, int sort, String remark) {}

    /** SKU 规格：sku/名称/品牌序号/品类编码/单位/挂牌价(元)/成本(元)/状态/适用门店类型/时长/服务大类/风险标签 */
    private record SkuSpec(String sku, String name, int brandIdx, String catCode, String unit,
                           long listYuan, long costYuan, String status, String storeTypes, int durationMin,
                           String serviceCategory, String riskTags) {}

    /** 价目规格：sku/原价(元)/会员价(元)/活动价(元,null=无)/状态/待审批会员价(元,null=无)/待审批活动价(元)/原因 */
    private record PriceSpec(String sku, long originalYuan, long memberYuan, Long promoYuan, String status,
                             Long pendingMemberYuan, Long pendingPromoYuan, String reason) {}

    private final ProductBrandRepository brandRepo;
    private final ProductCategoryRepository categoryRepo;
    private final ProjectService projectService;
    private final PricelistService pricelistService;
    private final String datasourceUrl;

    public StorePriceCatalogDataInitializer(ProductBrandRepository brandRepo,
                                            ProductCategoryRepository categoryRepo,
                                            ProjectService projectService,
                                            PricelistService pricelistService,
                                            @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.brandRepo = brandRepo;
        this.categoryRepo = categoryRepo;
        this.projectService = projectService;
        this.pricelistService = pricelistService;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedCatalog();
        seedPrices();
    }

    private void seedCatalog() {
        if (brandRepo.count() > 0) {
            log.info("品牌档案已存在（{} 个），跳过项目目录播种", brandRepo.count());
            return;
        }
        List<BrandSpec> brands = List.of(
                new BrandSpec("BR-ALLERGAN", "艾尔建", "Allergan", "美国/爱尔兰", "艾尔建信息咨询(上海)有限公司",
                        "ACTIVE", "#5B8DEF", "全球医美制药龙头，肉毒素/玻尿酸头部品牌"),
                new BrandSpec("BR-BLOOMAGE", "华熙生物", "Bloomage", "中国山东", "华熙生物科技股份有限公司",
                        "ACTIVE", "#22C55E", "透明质酸全产业链"),
                new BrandSpec("BR-SINOGEN", "中韩光电", "Sinogen", "中国北京", "北京中韩光电科技有限公司",
                        "ACTIVE", "#F59E0B", "光电仪器设备与耗材"),
                new BrandSpec("BR-LUMENIS", "科医人", "Lumenis", "以色列", "科医人医疗激光设备有限公司",
                        "INACTIVE", "#8B5CF6", "医美能量源设备"),
                new BrandSpec("BR-GALD", "高德美", "Galderma", "瑞士", "高德美（上海）医疗器械有限公司",
                        "ACTIVE", "#06B6D4", "瑞蓝玻尿酸原厂，皮肤科学与医美填充"),
                new BrandSpec("BR-SBM", "圣博玛", "SBM", "中国长春", "长春圣博玛生物材料有限公司",
                        "ACTIVE", "#EC4899", "艾维岚童颜针（再生抗衰）国产原厂"),
                new BrandSpec("BR-PENINSULA", "半岛医疗", "Peninsula", "中国深圳", "深圳半岛医疗集团股份有限公司",
                        "ACTIVE", "#14B8A6", "超声炮等国产能量源设备龙头"),
                new BrandSpec("BR-BTL", "BTL", "BTL", "英国", "BTL 医疗（中国）",
                        "ACTIVE", "#EF4444", "美修斯/隔空溶脂等形体管理设备"),
                new BrandSpec("BR-CANFIELD", "Canfield", "Canfield", "美国", "Canfield Scientific（中国代理）",
                        "ACTIVE", "#6366F1", "VISIA 皮肤检测影像系统原厂"));
        Map<Integer, ProductBrand> brandByIdx = new HashMap<>();
        for (int i = 0; i < brands.size(); i++) {
            BrandSpec s = brands.get(i);
            brandByIdx.put(i, projectService.seedBrand(s.code(), s.name(), s.shortName(), s.origin(),
                    s.supplier(), s.status(), s.logoColor(), s.remark(), OP));
        }

        List<CatSpec> cats = List.of(
                new CatSpec("CT-INJECT", "注射美容", 0, null, 1, "肉毒素、玻尿酸注射类"),
                new CatSpec("CT-BTX", "肉毒素", 0, "CT-INJECT", 1, null),
                new CatSpec("CT-FILLER", "玻尿酸填充", 0, "CT-INJECT", 2, null),
                new CatSpec("CT-BODY", "形体减脂", 0, null, 2, "冷冻溶脂等形体管理"),
                new CatSpec("CT-HA", "水光补水", 1, null, 1, null),
                new CatSpec("CT-SKINCARE", "功能性护肤", 1, null, 2, null),
                new CatSpec("CT-LASER", "激光治疗", 2, null, 1, null),
                new CatSpec("CT-THERMO", "射频紧致", 2, null, 2, "热玛吉/超声类射频"),
                new CatSpec("CT-IPL", "光子嫩肤", 3, null, 1, null),
                new CatSpec("CT-GD-INJECT", "注射美容", 4, null, 1, null),
                new CatSpec("CT-GD-FILLER", "玻尿酸填充", 4, "CT-GD-INJECT", 1, null),
                new CatSpec("CT-SBM-INJECT", "注射美容", 5, null, 1, null),
                new CatSpec("CT-SBM-REGEN", "再生抗衰", 5, "CT-SBM-INJECT", 1, "童颜针/少女针再生填充"),
                new CatSpec("CT-PEN-ULTRA", "超声抗衰", 6, null, 1, "超声炮"),
                new CatSpec("CT-BTL-BODY", "形体管理", 7, null, 1, "美修斯塑形"),
                new CatSpec("CT-CF-EXAM", "检测咨询", 8, null, 1, "VISIA 皮肤检测"));
        Map<String, ProductCategory> catByCode = new HashMap<>();
        for (CatSpec s : cats) {
            Long parentId = s.parentCode() == null ? null : catByCode.get(s.parentCode()).getId();
            ProductCategory c = projectService.seedCategory(s.code(), s.name(), brandByIdx.get(s.brandIdx()).getId(),
                    parentId, s.sort(), s.remark(), OP);
            catByCode.put(s.code(), c);
        }

        String all = "FLAGSHIP,COMMUNITY,CLINIC";
        String fc = "FLAGSHIP,CLINIC";
        String flagship = "FLAGSHIP";
        List<SkuSpec> skus = List.of(
                new SkuSpec("AGN-BTX-100", "保妥适 100U 瘦脸针", 0, "CT-INJECT", "次", 3800, 1650,
                        "ACTIVE", all, 30, "INJECTION", "INJECTION"),
                new SkuSpec("AGN-JUV-1ML", "乔雅登极致 1ml 玻尿酸", 0, "CT-INJECT", "支", 6800, 3200,
                        "ACTIVE", fc, 45, "INJECTION", "INJECTION"),
                new SkuSpec("AGN-COOL-BODY", "酷塑冷冻溶脂（单部位）", 0, "CT-BODY", "部位", 8800, 3600,
                        "ACTIVE", all, 60, "BODY", "HIGH_ENERGY,PREGNANCY_RISK"),
                new SkuSpec("HX-RST-2.5ML", "润致娃娃针 2.5ml", 1, "CT-HA", "支", 1980, 680,
                        "ACTIVE", all, 40, "INJECTION", "INJECTION,ANESTHESIA"),
                new SkuSpec("HX-QUADHA", "润百颜次抛精华（疗程）", 1, "CT-SKINCARE", "盒", 880, 220,
                        "ACTIVE", all, 0, "SKINCARE", null),
                new SkuSpec("HX-AQUA-BASE", "基础水光针", 1, "CT-HA", "次", 980, 260,
                        "ACTIVE", all, 30, "INJECTION", "INJECTION,ANESTHESIA"),
                new SkuSpec("HX-BUBBLE", "小气泡深层清洁护理", 1, "CT-SKINCARE", "次", 380, 90,
                        "ACTIVE", all, 45, "SKINCARE", null),
                new SkuSpec("ZH-THERMAGE-FL", "热玛吉FLX 面部900发", 2, "CT-THERMO", "部位", 19800, 7200,
                        "ACTIVE", flagship, 90, "LASER", "LASER,HIGH_ENERGY,PREGNANCY_RISK"),
                new SkuSpec("ZH-PICOWAY", "超皮秒全模式", 2, "CT-LASER", "次", 2980, 980,
                        "ACTIVE", all, 40, "LASER", "LASER"),
                new SkuSpec("LUM-M22", "M22王者之冠 光子嫩肤", 3, "CT-IPL", "次", 1280, 420,
                        "INACTIVE", all, 30, "LASER", "LASER"),
                new SkuSpec("GD-RESTYLANE-2", "瑞蓝2号玻尿酸 1ml", 4, "CT-GD-FILLER", "支", 6800, 3100,
                        "ACTIVE", all, 30, "INJECTION", "INJECTION"),
                new SkuSpec("SBM-AETHETE", "艾维岚童颜针（少女针）", 5, "CT-SBM-REGEN", "支", 18800, 8600,
                        "ACTIVE", all, 40, "INJECTION", "INJECTION"),
                new SkuSpec("PEN-ULTRA-PRO", "半岛超声炮（面部）", 6, "CT-PEN-ULTRA", "次", 19800, 7600,
                        "ACTIVE", all, 70, "LASER", "LASER,HIGH_ENERGY,PREGNANCY_RISK"),
                new SkuSpec("BTL-EMSCULPT", "BTL美修斯美体塑形", 7, "CT-BTL-BODY", "次", 1280, 480,
                        "ACTIVE", all, 40, "BODY", "PREGNANCY_RISK"),
                new SkuSpec("CF-VISIA", "VISIA 皮肤检测", 8, "CT-CF-EXAM", "次", 200, 60,
                        "ACTIVE", all, 15, "EXAM", null));
        for (SkuSpec s : skus) {
            projectService.seedSku(s.sku(), s.name(), brandByIdx.get(s.brandIdx()).getId(),
                    catByCode.get(s.catCode()).getId(), s.unit(),
                    s.listYuan() * 100, s.costYuan() * 100, s.status(), s.storeTypes(),
                    s.durationMin(), s.serviceCategory(), s.riskTags(), null, OP);
        }
        log.info("项目目录播种完成：{} 品牌 / {} 品类 / {} 项目 SKU", brands.size(), cats.size(), skus.size());
    }

    private void seedPrices() {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过 SST01 门店价目播种；正式栈价目由价格页面审批产生",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (pricelistService.countByStore(STORE) > 0) {
            log.info("门店价目已存在（SST01 {} 条），跳过价目播种", pricelistService.countByStore(STORE));
            return;
        }
        List<PriceSpec> prices = List.of(
                new PriceSpec("GD-RESTYLANE-2", 6800, 5800, 5280L, "ACTIVE", null, null, null),
                new PriceSpec("AGN-BTX-100", 4800, 4200, null, "ACTIVE", null, null, null),
                new PriceSpec("SBM-AETHETE", 18800, 16800, null, "PENDING", 15800L, 14800L,
                        "暑期抗衰活动，需配合整体促销方案下调"),
                new PriceSpec("ZH-THERMAGE-FL", 28800, 25800, 23800L, "ACTIVE", null, null, null),
                new PriceSpec("PEN-ULTRA-PRO", 19800, 17800, 16800L, "ACTIVE", null, null, null),
                new PriceSpec("LUM-M22", 1980, 1680, 1280L, "ACTIVE", null, null, null),
                new PriceSpec("HX-AQUA-BASE", 980, 780, 580L, "ACTIVE", null, null, null),
                new PriceSpec("HX-BUBBLE", 380, 280, 198L, "DISABLED", null, null, null),
                new PriceSpec("AGN-COOL-BODY", 8800, 7800, null, "ACTIVE", null, null, null),
                new PriceSpec("BTL-EMSCULPT", 1280, 980, 780L, "PENDING", 880L, null,
                        "新客拓客，下调体验价"),
                new PriceSpec("CF-VISIA", 200, 0, null, "ACTIVE", null, null, null));
        int pending = 0;
        for (PriceSpec s : prices) {
            boolean isPending = s.pendingMemberYuan() != null;
            pricelistService.seedPrice(STORE, s.sku(), s.originalYuan() * 100, s.memberYuan() * 100,
                    s.promoYuan() == null ? null : s.promoYuan() * 100, s.status(),
                    s.pendingMemberYuan() == null ? null : s.pendingMemberYuan() * 100,
                    s.pendingPromoYuan() == null ? null : s.pendingPromoYuan() * 100,
                    s.reason(), isPending ? MANAGER : null, OP);
            if (isPending) pending++;
        }
        log.info("门店价目播种完成：SST01 {} 条价目（{} 条待审批）", prices.size(), pending);
    }
}
