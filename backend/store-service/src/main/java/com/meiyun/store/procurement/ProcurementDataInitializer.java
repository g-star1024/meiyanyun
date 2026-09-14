package com.meiyun.store.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 采购供应链域种子（B49 卡5）：仅 meiyun_seed 库、supplier 表为空时幂等播种。
 *
 * <p>4 家供应商（SUP-004 资质到期停用，仅列表展示不可下单）+ 6 张采购单覆盖六态：
 * DRAFT / SUBMITTED(GROUP 档) / APPROVED(REGION 档) / PARTIAL(部分收货已联动库存) /
 * RECEIVED(一次收齐已联动库存) / CANCELLED。跨 SST01~03 三店，明细引用 HC-001~006 既有耗材 SKU。
 *
 * <p>必须晚于 {@link com.meiyun.store.bom.StoreBomDataInitializer}（@Order(50)）建档，
 * 否则 PARTIAL/RECEIVED 收货时 stockIn 抛 404「请先建档」；故定 @Order(60)。
 * 全部建单/状态流转/收货均走 {@link PurchaseOrderService}，保证单号、审计、库存联动与线上同一路径。
 */
@Component
@Order(60)
public class ProcurementDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcurementDataInitializer.class);

    private final SupplierRepository supplierRepo;
    private final PurchaseOrderService poService;
    private final String datasourceUrl;

    public ProcurementDataInitializer(SupplierRepository supplierRepo,
                                      PurchaseOrderService poService,
                                      @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.supplierRepo = supplierRepo;
        this.poService = poService;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过采购供应商/采购单播种",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (supplierRepo.count() > 0) {
            log.info("供应商档案已存在（{} 家），跳过采购域播种", supplierRepo.count());
            return;
        }

        Supplier s1 = poService.createSupplier("SUP-001", "华东医美耗材集采中心", "周敏", "13800001001",
                30, true, "ACTIVE", "集团框架协议供应商", "system");
        Supplier s2 = poService.createSupplier("SUP-002", "上海焕颜生物科技", "陈立", "13800001002",
                45, true, "ACTIVE", "水光原液/面膜主力供方", "system");
        Supplier s3 = poService.createSupplier("SUP-003", "杭州康丽达医疗器械", "林芳", "13800001003",
                60, true, "ACTIVE", "光子/射频类器械耗材", "system");
        poService.createSupplier("SUP-004", "临期小厂耗材经营部", "吴某", "13800001004",
                15, false, "INACTIVE", "经营资质到期，暂停合作待复审", "system");

        // 1) DRAFT 草稿（SST01，STORE 档）
        poService.createDraft("SST01", s2.getId(), "2026-09-30", "中秋活动备货（草稿待完善）",
                lines(line("HC-005", "医用修复面膜", "华熙生物", "片", 1200L, 200)), "system");

        // 2) SUBMITTED 待审批（SST01，GROUP 档：20×1200=24000 元 ≥2 万）；与 HC-004 期初 0 库存缺货叙事呼应
        PurchaseOrder p2 = poService.createDraft("SST01", s1.getId(), "2026-09-22", "热玛吉探头紧急补货",
                lines(line("HC-004", "热玛吉专用探头", "康丽达", "个", 120000L, 20)), "system");
        poService.submit(p2.getId(), "system");

        // 3) APPROVED 待入库（SST02，REGION 档：200×35=7000 元）
        PurchaseOrder p3 = poService.createDraft("SST02", s3.getId(), "2026-09-25", "光子项目冷凝胶补货",
                lines(line("HC-003", "光子嫩肤冷凝胶", "康丽达", "支", 3500L, 200)), "system");
        poService.submit(p3.getId(), "system");
        poService.approve(p3.getId(), "区域经理", "同意按协议价采购");

        // 4) PARTIAL 部分入库（SST02，STORE 档）：100 套先收 40，联动真实库存
        PurchaseOrder p4 = poService.createDraft("SST02", s1.getId(), "2026-09-20", "水光套包首批",
                lines(line("HC-001", "水光一次性耗材套包", "华东集采", "套", 1800L, 100)), "system");
        poService.submit(p4.getId(), "system");
        poService.approve(p4.getId(), "区域经理", null);
        poService.receive(p4.getId(), List.of(new PurchaseOrderService.ReceiveLineCmd("HC-001", 40)),
                "库管小王", "首批先到 40 套");

        // 5) RECEIVED 已入库（SST03，STORE 档）：两行一次收齐，联动真实库存
        PurchaseOrder p5 = poService.createDraft("SST03", s2.getId(), "2026-09-18", "日常耗材整单",
                lines(line("HC-006", "皮肤消毒液", "焕颜生物", "瓶", 800L, 100),
                        line("HC-002", "玻尿酸水光原液", "焕颜生物", "支", 6800L, 50)), "system");
        poService.submit(p5.getId(), "system");
        poService.approve(p5.getId(), "区域经理", null);
        poService.receive(p5.getId(),
                List.of(new PurchaseOrderService.ReceiveLineCmd("HC-006", 100),
                        new PurchaseOrderService.ReceiveLineCmd("HC-002", 50)),
                "库管小李", null);

        // 6) CANCELLED 已作废（SST03，STORE 档）：提交后作废
        PurchaseOrder p6 = poService.createDraft("SST03", s1.getId(), "2026-09-28", "重复申购作废",
                lines(line("HC-005", "医用修复面膜", "华东集采", "片", 1200L, 50)), "system");
        poService.submit(p6.getId(), "system");
        poService.cancel(p6.getId(), "system", "与 SST03 既有采购计划重复，作废");

        log.info("采购域播种完成：4 家供应商（1 家停用）+ 6 张采购单覆盖 DRAFT/SUBMITTED/APPROVED/PARTIAL/RECEIVED/CANCELLED 六态");
    }

    private static List<PurchaseOrderService.PoLineCmd> lines(PurchaseOrderService.PoLineCmd... arr) {
        List<PurchaseOrderService.PoLineCmd> list = new ArrayList<>();
        for (PurchaseOrderService.PoLineCmd l : arr) list.add(l);
        return list;
    }

    /** 单价单位「分」（元 ×100）。 */
    private static PurchaseOrderService.PoLineCmd line(String sku, String name, String brand,
                                                       String unit, long priceFen, int qty) {
        return new PurchaseOrderService.PoLineCmd(sku, name, brand, unit, priceFen, qty);
    }
}
