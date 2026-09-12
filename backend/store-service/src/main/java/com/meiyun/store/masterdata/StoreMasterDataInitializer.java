package com.meiyun.store.masterdata;

import com.meiyun.store.equipment.Equipment;
import com.meiyun.store.equipment.EquipmentRepository;
import com.meiyun.store.equipment.EquipmentService;
import com.meiyun.store.room.RoomService;
import com.meiyun.store.room.TreatmentBedRepository;
import com.meiyun.store.room.TreatmentRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 房间床位 + 设备仪器主数据种子（B13，DESIGN-P4）：SST01 为空时幂等播种
 * 9 间房 16 张床位（仅 A03-2 维护中）与 8 台设备（含校准/维保记录）。
 *
 * <p>数据口径对齐前端 mock 演示态：床位仅落主数据 + 维护停用态（D1，交易态不落库）；
 * 设备金额「元→分」×100，日期相对今天偏移（LocalDate，精确到天）。
 * 复用 {@link RoomService}/{@link EquipmentService} 的 seed* 辅助方法（不写审计/业务日志）。
 * Order(60) 晚于库存/配方种子 Order(50)。
 */
@Component
@Order(60)
public class StoreMasterDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreMasterDataInitializer.class);

    private static final String STORE = "SST01";
    private static final String OP = "system";

    /** 房间规格：编号/名称/类型/床位编号（末位标 M 表示该床维护中） */
    private record RoomSpec(String code, String name, String type, List<String> beds, String maintBed, String maintReason) {}

    /** 设备维保记录：类型/发生于几天前/经办人/服务商/内容/下次日期(天偏移, null=无)/费用(元) */
    private record RecSpec(String type, int daysAgo, String by, String vendor, String summary,
                           Integer nextInDays, long costYuan) {}

    /** 设备规格：资产编号/名称/品牌/型号/分类/位置/状态/购置年限前/购置额(元)/年限/折旧(元)/下次校准(天)/下次维保(天)/备注/记录 */
    private record EqSpec(String assetNo, String name, String brand, String model, String category,
                          String location, String status, double yearsAgo, long purchaseYuan, int lifespanYears,
                          long depreciatedYuan, Integer nextCalDays, Integer nextMaintDays, String note,
                          List<RecSpec> recs) {}

    private final TreatmentBedRepository bedRepo;
    private final EquipmentRepository eqRepo;
    private final RoomService roomService;
    private final EquipmentService equipmentService;
    private final String datasourceUrl;

    public StoreMasterDataInitializer(TreatmentBedRepository bedRepo,
                                      EquipmentRepository eqRepo,
                                      RoomService roomService,
                                      EquipmentService equipmentService,
                                      @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.bedRepo = bedRepo;
        this.eqRepo = eqRepo;
        this.roomService = roomService;
        this.equipmentService = equipmentService;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过房间床位/设备仪器演示主数据播种；正式栈主数据由门店页面建档产生",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        seedRooms();
        seedEquipments();
    }

    private void seedRooms() {
        if (bedRepo.count() > 0) {
            log.info("床位档案已存在（{} 张），跳过房间床位播种", bedRepo.count());
            return;
        }
        List<RoomSpec> rooms = List.of(
                new RoomSpec("A01", "激光治疗室", "TREATMENT", List.of("A01-1", "A01-2"), null, null),
                new RoomSpec("A02", "射频治疗室", "TREATMENT", List.of("A02-1", "A02-2"), null, null),
                new RoomSpec("A03", "超声刀室", "TREATMENT", List.of("A03-1", "A03-2"), "A03-2", "治疗床导轨异响，待维修"),
                new RoomSpec("B01", "注射室 1", "TREATMENT", List.of("B01-1"), null, null),
                new RoomSpec("B02", "注射室 2", "TREATMENT", List.of("B02-1", "B02-2"), null, null),
                new RoomSpec("C01", "VIP 咨询室", "CONSULT", List.of("C01-1"), null, null),
                new RoomSpec("C02", "咨询室", "CONSULT", List.of("C02-1"), null, null),
                new RoomSpec("D01", "术后观察室", "OBSERVE", List.of("D01-1", "D01-2", "D01-3"), null, null),
                new RoomSpec("D02", "恢复室", "RECOVERY", List.of("D02-1", "D02-2"), null, null));
        int bedCount = 0;
        for (RoomSpec spec : rooms) {
            TreatmentRoom r = roomService.seedRoom(STORE, spec.code(), spec.name(), spec.type(), OP);
            for (String bedCode : spec.beds()) {
                boolean maint = bedCode.equals(spec.maintBed());
                roomService.seedBed(STORE, r.getId(), bedCode, maint ? "MAINTENANCE" : "OK",
                        maint ? spec.maintReason() : null, OP);
                bedCount++;
            }
        }
        log.info("房间床位播种完成：{} 间房 / {} 张床位（A03-2 维护中）", rooms.size(), bedCount);
    }

    private void seedEquipments() {
        if (eqRepo.countByStoreCode(STORE) > 0) {
            log.info("设备台账已存在（{} 台），跳过设备播种", eqRepo.countByStoreCode(STORE));
            return;
        }
        LocalDate today = LocalDate.now();
        List<EqSpec> eqs = List.of(
                new EqSpec("EQ-L001", "皮秒激光治疗仪", "赛诺秀", "PicoSure", "LASER", "A01 激光治疗室", "NORMAL",
                        2, 680000, 8, 168000, 60, 20, null, List.of(
                        new RecSpec("CALIBRATION", 120, "苏晴（店长）", "赛诺秀原厂", "年度能量校准，输出稳定", 60, 4800),
                        new RecSpec("MAINTENANCE", 70, "吴桐（运营）", "华东医械", "季度光路除尘与手柄检测", 20, 1200))),
                new EqSpec("EQ-R002", "热玛吉射频治疗仪", "Solta", "Thermage FLX", "RF", "A02 射频治疗室", "CALIBRATING",
                        1.5, 520000, 8, 96000, 2, 90, null, List.of(
                        new RecSpec("MAINTENANCE", 60, "吴桐（运营）", "Solta 中国", "半年度常规维保，手柄接触面更换", 90, 3600),
                        new RecSpec("CALIBRATION", 360, "苏晴（店长）", "Solta 中国", "年度能量校准", 2, 5200))),
                new EqSpec("EQ-U003", "超声刀治疗仪", "Merz", "Ulthera", "ULTRASOUND", "A03 超声刀室", "REPAIRING",
                        3, 420000, 8, 196000, 30, 120, "E07 报错，手柄无法出能，等待配件", List.of(
                        new RecSpec("REPAIR", 3, "吴桐（运营）", "Merz 售后", "报修 E07，初步判定手柄主板故障，配件订购中", 120, 0),
                        new RecSpec("CALIBRATION", 330, "苏晴（店长）", "Merz 售后", "年度校准通过", 30, 4200))),
                new EqSpec("EQ-I004", "水光注射仪", "CUSM", "Vital Injector 2", "INJECTION", "B01 注射室", "NORMAL",
                        1, 38000, 5, 6800, 120, 40, null, List.of(
                        new RecSpec("MAINTENANCE", 50, "顾屿（主治医师）", "代理工程师", "注射压力校准，密封圈更换", 40, 480))),
                new EqSpec("EQ-M005", "多参数监护仪", "迈瑞", "uMEC12", "MONITOR", "D01 术后观察室", "NORMAL",
                        4, 26000, 8, 13200, -5, 60, "校准已过期 5 天，需尽快安排", List.of(
                        new RecSpec("CALIBRATION", 370, "苏晴（店长）", "迈瑞医疗", "年度计量校准", -5, 800))),
                new EqSpec("EQ-L006", "二氧化碳激光治疗仪", "科英", "KL-R", "LASER", "A01 激光治疗室", "DISABLED",
                        7, 128000, 8, 112000, null, null, "导光臂老化，维修成本过高，计划资产报废", List.of(
                        new RecSpec("REPAIR", 30, "苏晴（店长）", "科英售后", "导光臂损坏，维修费报价 2.8 万，建议停用", null, 0))),
                new EqSpec("EQ-O007", "冷喷补水仪", "日韩", "A-One", "OTHER", "D02 恢复室", "NORMAL",
                        0.5, 4800, 5, 480, null, 180, null, List.of(
                        new RecSpec("MAINTENANCE", 5, "周敏（美容师）", null, "内部水垢清洁", 180, 0))),
                new EqSpec("EQ-R008", "黄金射频微针", "Jeisys", "Genius", "RF", "A02 射频治疗室", "NORMAL",
                        2, 260000, 8, 56000, 10, 15, null, List.of(
                        new RecSpec("MAINTENANCE", 75, "吴桐（运营）", "Jeisys 代理", "微针头更换与频率校准", 15, 2800))));

        int recCount = 0;
        for (EqSpec s : eqs) {
            Equipment e = equipmentService.seedEquipment(STORE, s.assetNo(), s.name(), s.brand(), s.model(),
                    s.category(), s.location(), s.status(),
                    today.minusDays(Math.round(s.yearsAgo() * 365)),
                    s.purchaseYuan() * 100, s.lifespanYears(), s.depreciatedYuan() * 100,
                    s.nextCalDays() == null ? null : today.plusDays(s.nextCalDays()),
                    s.nextMaintDays() == null ? null : today.plusDays(s.nextMaintDays()),
                    s.note(), OP);
            for (RecSpec r : s.recs()) {
                equipmentService.seedRecord(STORE, e.getId(), r.type(), today.minusDays(r.daysAgo()),
                        r.by(), r.vendor(), r.summary(),
                        r.nextInDays() == null ? null : today.plusDays(r.nextInDays()),
                        r.costYuan() * 100);
                recCount++;
            }
        }
        log.info("设备仪器播种完成：{} 台设备 / {} 条校准维保记录", eqs.size(), recCount);
    }
}
