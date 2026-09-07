package com.meiyun.store.equipment;

import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备仪器域服务（B13）：台账查询、建档、状态变更、校准/维保/维修记录登记。
 *
 * <p>金额：库存 Long「分」，出参转「元」（fenToYuan）；前端传「分」（yuan2fen 在前端完成）。
 * 记录回写规则与前端 mock 同构：CALIBRATION+nextAt 回写下次校准日；MAINTENANCE/REPAIR+nextAt
 * 回写下次维保日；CALIBRATION 且 CALIBRATING → NORMAL；REPAIR 且 REPAIRING → NORMAL；
 * cost 累加折旧（封顶购置金额）。数据域由 Controller 解析 storeCode，本服务只按店操作。
 */
@Service
public class EquipmentService {

    private static final List<String> CATEGORIES =
            List.of("LASER", "RF", "ULTRASOUND", "INJECTION", "MONITOR", "OTHER");
    private static final List<String> STATUSES =
            List.of("NORMAL", "CALIBRATING", "REPAIRING", "DISABLED");
    private static final List<String> REC_TYPES =
            List.of("CALIBRATION", "MAINTENANCE", "REPAIR");

    private final EquipmentRepository eqRepo;
    private final EquipmentMaintenanceRepository recRepo;
    private final ConsumableAuditRecorder audit;

    public EquipmentService(EquipmentRepository eqRepo,
                            EquipmentMaintenanceRepository recRepo,
                            ConsumableAuditRecorder audit) {
        this.eqRepo = eqRepo;
        this.recRepo = recRepo;
        this.audit = audit;
    }

    /** 400 参数错误（中文） */
    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** 设备台账（含校准/维保记录嵌套，金额单位「元」）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEquipments(String storeCode, String category, String status, String keyword) {
        if (isBlank(storeCode)) return List.of();
        String cat = isBlank(category) || "ALL".equals(category) ? null : category.trim();
        String st = isBlank(status) || "ALL".equals(status) ? null : status.trim();
        String kw = isBlank(keyword) ? null : keyword.trim();
        List<Equipment> list = eqRepo.search(storeCode.trim(), cat, st, kw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Equipment e : list) {
            out.add(toView(e));
        }
        return out;
    }

    /** 单台设备（含记录）；不存在/跨店 404。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getEquipment(String storeCode, Long id) {
        return toView(mustFind(storeCode, id));
    }

    /** 设备建档；金额单位「分」，日期入参为 ISO 字符串（yyyy-MM-dd 或完整 ISO 时间，截前 10 位）。 */
    @Transactional
    public Equipment createEquipment(String storeCode, String assetNo, String name, String brand, String model,
                                     String category, String location, String status,
                                     String purchasedAt, Long purchaseAmountFen, Integer lifespanYears,
                                     String nextCalibrationAt, String nextMaintenanceAt, String note,
                                     String operator) {
        if (isBlank(storeCode) || isBlank(assetNo) || isBlank(name) || isBlank(category)) {
            throw badReq("门店、资产编号、设备名称、设备分类均不能为空");
        }
        if (!CATEGORIES.contains(category.trim())) {
            throw badReq("设备分类仅支持 LASER/RF/ULTRASOUND/INJECTION/MONITOR/OTHER");
        }
        String st = isBlank(status) ? "NORMAL" : status.trim();
        if (!STATUSES.contains(st)) {
            throw badReq("设备状态仅支持 NORMAL/CALIBRATING/REPAIRING/DISABLED");
        }
        long fen = purchaseAmountFen == null ? 0L : Math.max(0L, purchaseAmountFen);
        int years = lifespanYears == null || lifespanYears <= 0 ? 8 : lifespanYears;
        if (eqRepo.findByStoreCodeAndAssetNo(storeCode, assetNo.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "本店已存在资产编号「" + assetNo + "」，请勿重复建档");
        }
        Equipment e = new Equipment();
        e.setStoreCode(storeCode);
        e.setAssetNo(assetNo.trim());
        e.setName(name.trim());
        e.setBrand(blankToNull(brand));
        e.setModel(blankToNull(model));
        e.setCategory(category.trim());
        e.setLocation(isBlank(location) ? "未设置" : location.trim());
        e.setStatus(st);
        e.setPurchasedAt(parseDate(purchasedAt, LocalDate.now()));
        e.setPurchaseAmountFen(fen);
        e.setLifespanYears(years);
        e.setDepreciatedFen(0L);
        e.setNextCalibrationAt(parseDate(nextCalibrationAt, null));
        e.setNextMaintenanceAt(parseDate(nextMaintenanceAt, null));
        e.setNote(blankToNull(note));
        e.setCreatedBy(operator);
        e.setUpdatedBy(operator);
        eqRepo.save(e);

        audit.record("EQUIPMENT", "EQ-" + e.getId(), operator, "新建设备",
                String.format("{\"storeCode\":%s,\"assetNo\":%s,\"name\":%s,\"category\":%s,\"purchaseAmountFen\":%d}",
                        jsonStr(storeCode), jsonStr(e.getAssetNo()), jsonStr(e.getName()),
                        jsonStr(e.getCategory()), fen));
        return e;
    }

    /** 设备状态变更（NORMAL/CALIBRATING/REPAIRING/DISABLED），可附带备注。 */
    @Transactional
    public void setStatus(String storeCode, Long id, String status, String note, String operator) {
        if (isBlank(status) || !STATUSES.contains(status.trim())) {
            throw badReq("设备状态仅支持 NORMAL/CALIBRATING/REPAIRING/DISABLED");
        }
        Equipment e = mustFind(storeCode, id);
        String old = e.getStatus();
        e.setStatus(status.trim());
        if (!isBlank(note)) e.setNote(note.trim());
        e.setUpdatedBy(operator);
        e.setUpdatedAt(OffsetDateTime.now());
        eqRepo.save(e);
        audit.record("EQUIPMENT", "EQ-" + e.getId(), operator, "设备状态变更",
                String.format("{\"storeCode\":%s,\"assetNo\":%s,\"from\":%s,\"to\":%s}",
                        jsonStr(storeCode), jsonStr(e.getAssetNo()), jsonStr(old), jsonStr(status.trim())));
    }

    /**
     * 登记校准/维保/维修记录并回写设备：
     * CALIBRATION+nextAt→下次校准日；MAINTENANCE/REPAIR+nextAt→下次维保日；
     * CALIBRATION 且 CALIBRATING→NORMAL；REPAIR 且 REPAIRING→NORMAL；cost 累加折旧（封顶购置额）。
     */
    @Transactional
    public void addRecord(String storeCode, Long id, String type, String summary, String vendor,
                          String at, String nextAt, Long costFen, String operator) {
        if (isBlank(type) || !REC_TYPES.contains(type.trim())) {
            throw badReq("记录类型仅支持 CALIBRATION/MAINTENANCE/REPAIR");
        }
        if (isBlank(summary)) throw badReq("记录内容/结果不能为空");
        Equipment e = mustFind(storeCode, id);
        long cost = costFen == null ? 0L : Math.max(0L, costFen);
        LocalDate next = parseDate(nextAt, null);

        EquipmentMaintenance r = new EquipmentMaintenance();
        r.setStoreCode(storeCode);
        r.setEquipmentId(e.getId());
        r.setType(type.trim());
        r.setOccurredAt(parseDate(at, LocalDate.now()));
        r.setActor(operator);
        r.setVendor(blankToNull(vendor));
        r.setSummary(summary.trim());
        r.setNextAt(next);
        r.setCostFen(cost);
        recRepo.save(r);

        if ("CALIBRATION".equals(r.getType()) && next != null) e.setNextCalibrationAt(next);
        if (("MAINTENANCE".equals(r.getType()) || "REPAIR".equals(r.getType())) && next != null) {
            e.setNextMaintenanceAt(next);
        }
        if ("CALIBRATION".equals(r.getType()) && "CALIBRATING".equals(e.getStatus())) e.setStatus("NORMAL");
        if ("REPAIR".equals(r.getType()) && "REPAIRING".equals(e.getStatus())) e.setStatus("NORMAL");
        if (cost > 0) {
            e.setDepreciatedFen(Math.min(e.getPurchaseAmountFen(), e.getDepreciatedFen() + cost));
        }
        e.setUpdatedBy(operator);
        e.setUpdatedAt(OffsetDateTime.now());
        eqRepo.save(e);

        audit.record("EQUIPMENT", "EQ-" + e.getId(), operator, "新增" + recTypeLabel(r.getType()) + "记录",
                String.format("{\"storeCode\":%s,\"assetNo\":%s,\"type\":%s,\"costFen\":%d,\"nextAt\":%s}",
                        jsonStr(storeCode), jsonStr(e.getAssetNo()), jsonStr(r.getType()), cost,
                        next == null ? "null" : jsonStr(next.toString())));
    }

    // ---- 内部辅助（含 DataInitializer 复用） ----

    /** 设备出参视图：金额转元、日期转 yyyy-MM-dd、记录嵌套倒序。 */
    private Map<String, Object> toView(Equipment e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("storeCode", e.getStoreCode());
        row.put("assetNo", e.getAssetNo());
        row.put("name", e.getName());
        row.put("brand", e.getBrand());
        row.put("model", e.getModel());
        row.put("category", e.getCategory());
        row.put("location", e.getLocation());
        row.put("status", e.getStatus());
        row.put("purchasedAt", e.getPurchasedAt() == null ? null : e.getPurchasedAt().toString());
        row.put("purchaseAmount", fenToYuan(e.getPurchaseAmountFen()));
        row.put("lifespanYears", e.getLifespanYears());
        row.put("depreciated", fenToYuan(e.getDepreciatedFen()));
        row.put("nextCalibrationAt", e.getNextCalibrationAt() == null ? null : e.getNextCalibrationAt().toString());
        row.put("nextMaintenanceAt", e.getNextMaintenanceAt() == null ? null : e.getNextMaintenanceAt().toString());
        row.put("note", e.getNote());
        List<Map<String, Object>> recs = new ArrayList<>();
        for (EquipmentMaintenance r : recRepo.findByEquipmentIdOrderByOccurredAtDescIdDesc(e.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("type", r.getType());
            m.put("at", r.getOccurredAt() == null ? null : r.getOccurredAt().toString());
            m.put("by", r.getActor());
            m.put("vendor", r.getVendor());
            m.put("summary", r.getSummary());
            m.put("nextAt", r.getNextAt() == null ? null : r.getNextAt().toString());
            m.put("cost", fenToYuan(r.getCostFen()));
            recs.add(m);
        }
        row.put("records", recs);
        return row;
    }

    private Equipment mustFind(String storeCode, Long id) {
        Equipment e = eqRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在：" + id));
        if (!e.getStoreCode().equals(storeCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本店不存在该设备");
        }
        return e;
    }

    /** 分→元（四舍五入到分，与 ConsumableService.fenToYuan 同口径）。 */
    private static double fenToYuan(Long fen) {
        if (fen == null) return 0.0;
        return Math.round(fen) / 100.0;
    }

    private static String recTypeLabel(String t) {
        return switch (t) {
            case "CALIBRATION" -> "校准";
            case "MAINTENANCE" -> "维保";
            case "REPAIR" -> "维修";
            default -> "维保";
        };
    }

    /**
     * 解析日期入参：接受 yyyy-MM-dd 或完整 ISO 时间（截前 10 位）；空串返回 fallback。
     * 前端传 new Date().toISOString()（如 2026-09-07T03:12:00.000Z），截取日期部分即可。
     */
    static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        String t = s.trim();
        if (t.length() >= 10) {
            try {
                return LocalDate.parse(t.substring(0, 10));
            } catch (Exception ignore) {
                // fallthrough
            }
        }
        return fallback;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- 种子辅助（供 DataInitializer 复用，不写审计） ----

    /** 建设备（种子用）：金额传「分」，日期传 LocalDate（已按今天偏移算好）。 */
    @Transactional
    public Equipment seedEquipment(String storeCode, String assetNo, String name, String brand, String model,
                                   String category, String location, String status,
                                   LocalDate purchasedAt, long purchaseAmountFen, int lifespanYears,
                                   long depreciatedFen, LocalDate nextCalibrationAt, LocalDate nextMaintenanceAt,
                                   String note, String operator) {
        Equipment e = new Equipment();
        e.setStoreCode(storeCode);
        e.setAssetNo(assetNo);
        e.setName(name);
        e.setBrand(brand);
        e.setModel(model);
        e.setCategory(category);
        e.setLocation(location);
        e.setStatus(status);
        e.setPurchasedAt(purchasedAt);
        e.setPurchaseAmountFen(purchaseAmountFen);
        e.setLifespanYears(lifespanYears);
        e.setDepreciatedFen(depreciatedFen);
        e.setNextCalibrationAt(nextCalibrationAt);
        e.setNextMaintenanceAt(nextMaintenanceAt);
        e.setNote(note);
        e.setCreatedBy(operator);
        e.setUpdatedBy(operator);
        return eqRepo.save(e);
    }

    /** 建维保记录（种子用）：cost 传「分」，日期传 LocalDate；不回写设备（种子一次性给齐字段）。 */
    @Transactional
    public void seedRecord(String storeCode, Long equipmentId, String type, LocalDate occurredAt, String actor,
                           String vendor, String summary, LocalDate nextAt, long costFen) {
        EquipmentMaintenance r = new EquipmentMaintenance();
        r.setStoreCode(storeCode);
        r.setEquipmentId(equipmentId);
        r.setType(type);
        r.setOccurredAt(occurredAt);
        r.setActor(actor);
        r.setVendor(vendor);
        r.setSummary(summary);
        r.setNextAt(nextAt);
        r.setCostFen(costFen);
        recRepo.save(r);
    }
}
