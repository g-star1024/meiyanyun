package com.meiyun.store.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 卡项/疗程目录模板种子（B15，DESIGN-P4 §4.5/§六）：
 * 8 条集团通用模板（store_code 空串，全门店可见可售）——4 卡项（CD-001~004，CD-004 已下架）/
 * 4 疗程（CS-001~004，CS-004 已下架）。
 *
 * <p>全环境播种（主数据两栈都需要，同 B14 惯例）；复用 {@link CatalogService} 的 seedProduct
 * （不写审计）；count 门控幂等。金额「元→分」×100；Order(80) 晚于项目目录/门店价目种子 Order(70)。
 */
@Component
@Order(80)
public class StoreCatalogDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreCatalogDataInitializer.class);

    private static final String GROUP = "";
    private static final String OP = "system";

    /** 模板规格：编码/名称/类型/分类/次数/有效期天/售价(元)/划线价(元)/可转赠/状态/包含项目/说明 */
    private record CatalogSpec(String code, String name, String type, String category, int sessions,
                               int validityDays, long priceYuan, long originalPriceYuan, boolean transferable,
                               String status, List<String> includes, String description) {}

    private final CatalogService catalogService;

    public StoreCatalogDataInitializer(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (catalogService.countAll() > 0) {
            log.info("卡项疗程目录已存在（{} 条），跳过目录播种", catalogService.countAll());
            return;
        }
        List<CatalogSpec> specs = List.of(
                new CatalogSpec("CD-001", "焕颜抗衰储值卡", "CARD", "储值卡", 1, 365, 10000, 12000, true,
                        "ON_SHELF",
                        List.of("卡内余额 10000 元", "全场项目通用", "生日双倍积分"),
                        "储值 1 万送 2 千，全场项目通用，有效期 1 年，支持亲友转赠。"),
                new CatalogSpec("CD-002", "闺蜜分享次卡", "CARD", "次卡", 10, 180, 3980, 5800, true,
                        "ON_SHELF",
                        List.of("基础水光 10 次", "可多人共用", "含面膜 10 片"),
                        "10 次基础水光，支持与 1 位闺蜜共享，半年内有效。"),
                new CatalogSpec("CD-003", "VIP 至尊年卡", "CARD", "年卡", 1, 365, 58800, 88800, false,
                        "ON_SHELF",
                        List.of("全年光电项目不限次", "专属皮肤管家", "VIP 休息室", "生日月赠项目"),
                        "全年光电类项目不限次，本人使用，含专属管家服务。"),
                new CatalogSpec("CS-001", "热玛吉紧致疗程", "COURSE", "抗衰疗程", 3, 365, 68800, 86400, false,
                        "ON_SHELF",
                        List.of("热玛吉 FLX 面部 3 次", "每次配术后修复面膜", "VISIA 检测 2 次"),
                        "3 次热玛吉面部紧致，分 3-6 个月完成，含术后护理。"),
                new CatalogSpec("CS-002", "光子嫩肤亮肤疗程", "COURSE", "美肤疗程", 6, 180, 8800, 11880, true,
                        "ON_SHELF",
                        List.of("M22 光子嫩肤 6 次", "小气泡清洁 2 次", "医用面膜 6 片"),
                        "6 次光子嫩肤，改善肤色暗沉、毛孔粗大，半年有效。"),
                new CatalogSpec("CS-003", "瘦身塑形疗程", "COURSE", "形体疗程", 8, 120, 15800, 22400, false,
                        "ON_SHELF",
                        List.of("冷冻溶脂 4 部位", "BTL 塑形 4 次", "体脂检测 3 次"),
                        "4 个月完成，冷冻溶脂 + BTL 联合，定向塑形。"),
                new CatalogSpec("CS-004", "痘肌修复疗程", "COURSE", "美肤疗程", 10, 150, 6980, 9800, true,
                        "OFF_SHELF",
                        List.of("果酸焕肤 5 次", "红蓝光祛痘 5 次", "痘肌专用护理产品"),
                        "针对中重度痘痘肌，10 次系统调理，已下架待升级新版。"),
                new CatalogSpec("CD-004", "体验官次卡", "CARD", "次卡", 3, 90, 598, 1280, false,
                        "OFF_SHELF",
                        List.of("小气泡 1 次", "光子嫩肤 1 次", "水光基础 1 次"),
                        "新客体验卡，3 个项目 90 天内体验，活动结束已下架。"));
        for (CatalogSpec s : specs) {
            catalogService.seedProduct(GROUP, s.code(), s.name(), s.type(), s.category(), s.sessions(),
                    s.validityDays(), s.priceYuan(), s.originalPriceYuan(), s.transferable(), s.status(),
                    s.includes(), s.description(), OP);
        }
        long onShelf = specs.stream().filter(s -> "ON_SHELF".equals(s.status())).count();
        log.info("卡项疗程目录播种完成：{} 条集团通用模板（{} 条上架 / {} 条下架）",
                specs.size(), onShelf, specs.size() - onShelf);
    }
}
