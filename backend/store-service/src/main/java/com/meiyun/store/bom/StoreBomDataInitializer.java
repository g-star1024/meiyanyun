package com.meiyun.store.bom;

import com.meiyun.store.consumable.ConsumableRepository;
import com.meiyun.store.consumable.ConsumableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存/配方域种子（B10，DESIGN §6.6）：consumable 台账为空时幂等播种 6 店耗材 SKU + 初始库存，
 * project_bom 为空时播种 4 个高频治疗项目的集团模板配方。
 *
 * <p>背景：种子库历史上无任何耗材数据（前端空库回落演示数据），BOM 自动扣料需真实 SKU/库存才能端到端。
 * SKU 全店统一编码（集团配方可解析）；热玛吉探头 HC-004 在 SST01 初始库存为 0，
 * 用于演示「划扣成功但扣料库存不足 → bom_deduct_exception → 补货后重试 RESOLVED」。
 * 耗材建档走 {@link ConsumableService#createSku}（库存行 + PURCHASE 期初流水 + 移动均价一并落库）；
 * 配方行直接落库（固定 ID BOM-SEED-xxx，不与 BomNoGenerator 的 BOM+日期序号撞号）。
 */
@Component
@Order(50)
public class StoreBomDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreBomDataInitializer.class);

    private static final String[] STORES = {"SST01", "SST02", "SST03", "SST04", "SST05", "SST06"};

    /** SKU 目录：编码/名称/分类/单位/成本价(元)/安全库存/标准初始库存（SST01 的 HC-004 特殊为 0） */
    private record SkuSpec(String code, String name, String category, String unit,
                           long costYuan, int safety, int stock) {}

    private static final List<SkuSpec> SKUS = List.of(
            new SkuSpec("HC-001", "水光一次性耗材套包", "CONSUMABLE", "套", 18, 10, 50),
            new SkuSpec("HC-002", "玻尿酸水光原液", "PRODUCT", "支", 68, 8, 30),
            new SkuSpec("HC-003", "光子嫩肤冷凝胶", "CONSUMABLE", "支", 35, 8, 40),
            new SkuSpec("HC-004", "热玛吉专用探头", "CONSUMABLE", "个", 1200, 2, 8),
            new SkuSpec("HC-005", "医用修复面膜", "PRODUCT", "片", 12, 20, 100),
            new SkuSpec("HC-006", "皮肤消毒液", "CONSUMABLE", "瓶", 8, 5, 20));

    private final ConsumableRepository consumableRepo;
    private final ConsumableService consumableService;
    private final ProjectBomRepository bomRepo;
    private final String datasourceUrl;

    public StoreBomDataInitializer(ConsumableRepository consumableRepo,
                                   ConsumableService consumableService,
                                   ProjectBomRepository bomRepo,
                                   @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.consumableRepo = consumableRepo;
        this.consumableService = consumableService;
        this.bomRepo = bomRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedConsumables();
        seedProjectBoms();
    }

    private void seedConsumables() {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过门店耗材播种；正式栈耗材由库存页面建档产生",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (consumableRepo.count() > 0) {
            log.info("耗材台账已存在（{} 条），跳过耗材播种", consumableRepo.count());
            return;
        }
        int created = 0;
        for (String store : STORES) {
            for (SkuSpec s : SKUS) {
                // SST01 的热玛吉探头不期初入库（库存 0），演示划扣后扣料不足异常
                int initialQty = "SST01".equals(store) && "HC-004".equals(s.code()) ? 0 : s.stock();
                consumableService.createSku(store, s.code(), s.name(), s.category(),
                        null, s.unit(), s.costYuan() * 100, s.safety(), initialQty,
                        "美研供应链", "主库房-" + store, "system");
                created++;
            }
        }
        log.info("耗材台账播种完成：{} 个 SKU × {} 店 = {} 条（SST01 热玛吉探头库存 0 用于异常演示）",
                SKUS.size(), STORES.length, created);
    }

    private void seedProjectBoms() {
        if (bomRepo.count() > 0) {
            log.info("项目配方已存在（{} 条），跳过配方播种", bomRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<ProjectBom> boms = new ArrayList<>();
        // 集团模板（store_code 空串）：项目名与 txn_order/writeoff_record.project 勾兑
        boms.add(bom("BOM-SEED-001", "水光针单次", "HC-001", 1, now));
        boms.add(bom("BOM-SEED-002", "水光针单次", "HC-002", 1, now));
        boms.add(bom("BOM-SEED-003", "水光针单次", "HC-005", 1, now));
        boms.add(bom("BOM-SEED-004", "光子嫩肤", "HC-003", 1, now));
        boms.add(bom("BOM-SEED-005", "光子嫩肤", "HC-006", 1, now));
        boms.add(bom("BOM-SEED-006", "热玛吉全面部", "HC-004", 1, now));
        boms.add(bom("BOM-SEED-007", "热玛吉全面部", "HC-005", 1, now));
        boms.add(bom("BOM-SEED-008", "小气泡清洁", "HC-006", 1, now));
        boms.add(bom("BOM-SEED-009", "小气泡清洁", "HC-003", 1, now));
        bomRepo.saveAll(boms);
        log.info("项目配方播种完成：{} 条集团模板（水光针/光子嫩肤/热玛吉/小气泡）", boms.size());
    }

    private ProjectBom bom(String id, String projectName, String skuCode, int qty, OffsetDateTime now) {
        ProjectBom b = new ProjectBom();
        b.setBomId(id);
        b.setProjectName(projectName);
        b.setStoreCode(ProjectBomService.GROUP_TEMPLATE);
        b.setSkuCode(skuCode);
        b.setQty(qty);
        b.setEnabled(true);
        b.setCreatedBy("system");
        b.setUpdatedBy("system");
        b.setCreatedAt(now);
        b.setUpdatedAt(now);
        return b;
    }
}
