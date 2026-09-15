package com.meiyun.store.room;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 治疗室内部只读端点（P5-B49 卡12，服务间调用专用）：txn 调度中心取本店在用治疗室作为 ROOM 资源。
 *
 * <p>红线边界：房间建档/停用语义归 store 域，此端点仅以系统身份（X-Internal-Token，perms=["*"]）
 * 放行 {@code internal:catalog-read}，普通登录人无该权限 → 403；txn 域不直读 treatment_room 表。
 * 固定只返回治疗室类型（TREATMENT）且在用（ACTIVE）的房间，床位嵌套明细一并返回但调度侧不使用。
 */
@RestController
@RequestMapping("/api/stores/internal/rooms")
public class InternalRoomController {

    private final RoomService roomService;

    public InternalRoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /** 本店在用治疗室：GET /api/stores/internal/rooms?storeCode=SST01。 */
    @GetMapping
    @RequirePerm("internal:catalog-read")
    public List<Map<String, Object>> treatmentRooms(
            @RequestParam(name = "storeCode", required = false) String storeCode) {
        return roomService.listRooms(storeCode, "TREATMENT", "ACTIVE");
    }
}
