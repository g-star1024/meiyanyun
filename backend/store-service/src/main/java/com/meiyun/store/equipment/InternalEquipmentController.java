package com.meiyun.store.equipment;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备内部只读端点（P5-B51 卡4，服务间调用专用）：txn 调度中心取本店 NORMAL 态设备作为 DEVICE 资源。
 *
 * <p>红线边界：设备建档/校准/维保/停用语义归 store 域，此端点仅以系统身份（X-Internal-Token，
 * perms=["*"]）放行 {@code internal:catalog-read}，普通登录人无该权限 → 403；txn 域不直读
 * equipment 表。固定只返回正常（NORMAL）设备的轻量行（无维保记录嵌套），校准/维修/停用中设备
 * 不参与调度。
 */
@RestController
@RequestMapping("/api/stores/internal/equipments")
public class InternalEquipmentController {

    private final EquipmentService equipmentService;

    public InternalEquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    /** 本店 NORMAL 态设备：GET /api/stores/internal/equipments?storeCode=SST01。 */
    @GetMapping
    @RequirePerm("internal:catalog-read")
    public List<Map<String, Object>> normalEquipments(
            @RequestParam(name = "storeCode", required = false) String storeCode) {
        return equipmentService.listDispatchBriefs(storeCode);
    }
}
