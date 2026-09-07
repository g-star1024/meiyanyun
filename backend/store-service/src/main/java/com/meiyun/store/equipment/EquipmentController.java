package com.meiyun.store.equipment;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 设备仪器公开端点（B13，网关 /api/stores 路由进 store-service，路径不重写）。
 *
 * <p>台账/记录查询走 {@code equipment:view}；建档、状态变更、记录登记走 {@code equipment:edit}。
 * 金额入参单位「分」（前端元→分换算），出参单位「元」。
 * 数据域：STORE/SELF 强制本店；SUPER/GROUP/BRAND 可按 storeCode 参查/写，缺省写须显式带店。
 */
@RestController
@RequestMapping("/api/stores")
public class EquipmentController {

    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    /** 设备台账（含校准/维保记录嵌套）；金额单位「元」。 */
    @GetMapping("/equipments")
    @RequirePerm("equipment:view")
    public List<Map<String, Object>> list(@RequestParam(value = "storeCode", required = false) String storeCode,
                                          @RequestParam(value = "category", required = false) String category,
                                          @RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "keyword", required = false) String keyword) {
        return service.listEquipments(resolveStoreCode(storeCode), category, status, keyword);
    }

    /** 单台设备详情（含记录）。 */
    @GetMapping("/equipments/{id}")
    @RequirePerm("equipment:view")
    public Map<String, Object> get(@PathVariable("id") Long id,
                                   @RequestParam(value = "storeCode", required = false) String storeCode) {
        return service.getEquipment(resolveStoreCode(storeCode), id);
    }

    /** 设备建档；金额单位「分」，日期为 ISO 字符串。 */
    @PostMapping("/equipments")
    @RequirePerm("equipment:edit")
    public Map<String, Object> create(@RequestBody CreateEquipmentCmd cmd) {
        if (cmd == null) throw EquipmentService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String operator = u == null ? "系统" : u.staffName();
        String storeCode = resolveWriteStoreCode(cmd.storeCode());
        Equipment e = service.createEquipment(storeCode, cmd.assetNo(), cmd.name(), cmd.brand(), cmd.model(),
                cmd.category(), cmd.location(), cmd.status(), cmd.purchasedAt(),
                cmd.purchaseAmountFen(), cmd.lifespanYears(),
                cmd.nextCalibrationAt(), cmd.nextMaintenanceAt(), cmd.note(), operator);
        return Map.of("id", e.getId(), "assetNo", e.getAssetNo(), "storeCode", e.getStoreCode());
    }

    /** 设备状态变更（NORMAL/CALIBRATING/REPAIRING/DISABLED），可附带备注。 */
    @PostMapping("/equipments/{id}/status")
    @RequirePerm("equipment:edit")
    public Map<String, Object> setStatus(@PathVariable("id") Long id, @RequestBody StatusCmd cmd) {
        if (cmd == null) throw EquipmentService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String operator = u == null ? "系统" : u.staffName();
        service.setStatus(resolveWriteStoreCode(cmd.storeCode()), id, cmd.status(), cmd.note(), operator);
        return Map.of("ok", true);
    }

    /** 登记校准/维保/维修记录（回写下次日期/状态/折旧）；费用单位「分」。 */
    @PostMapping("/equipments/{id}/records")
    @RequirePerm("equipment:edit")
    public Map<String, Object> addRecord(@PathVariable("id") Long id, @RequestBody RecordCmd cmd) {
        if (cmd == null) throw EquipmentService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String operator = u == null ? "系统" : u.staffName();
        service.addRecord(resolveWriteStoreCode(cmd.storeCode()), id, cmd.type(), cmd.summary(),
                cmd.vendor(), cmd.at(), cmd.nextAt(), cmd.costFen(), operator);
        return Map.of("ok", true);
    }

    // ---- 数据域 ----

    /** 查询：门店/自助角色强制本店；超管/集团/品牌可用 storeCode 参（须在可见名单），缺省取本店。 */
    private String resolveStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return requested;
        }
        if (requested != null && !requested.isBlank()) {
            if (!DataScope.canReadStore(requested.trim())) {
                return "__NONE__";
            }
            return requested.trim();
        }
        return u.storeCode();
    }

    /** 写操作：门店/自助角色只能写本店；超管/集团/品牌写参须显式传 storeCode。 */
    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            if (requested == null || requested.isBlank()) {
                throw EquipmentService.badReq("请指定所属门店");
            }
            return requested.trim();
        }
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            throw EquipmentService.badReq("当前账号无所属门店，无法操作设备仪器");
        }
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode())) {
            throw EquipmentService.badReq("门店角色仅能操作本店设备仪器");
        }
        return u.storeCode();
    }

    /** 设备建档入参；金额 purchaseAmountFen 单位「分」，日期为 ISO 字符串。 */
    public record CreateEquipmentCmd(String storeCode, String assetNo, String name, String brand, String model,
                                     String category, String location, String status, String purchasedAt,
                                     Long purchaseAmountFen, Integer lifespanYears,
                                     String nextCalibrationAt, String nextMaintenanceAt, String note) {}

    /** 状态变更入参。 */
    public record StatusCmd(String storeCode, String status, String note) {}

    /** 校准/维保/维修记录入参；费用 costFen 单位「分」，at/nextAt 为 ISO 字符串。 */
    public record RecordCmd(String storeCode, String type, String summary, String vendor,
                            String at, String nextAt, Long costFen) {}
}
