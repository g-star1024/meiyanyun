package com.meiyun.store.room;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 房间/床位公开端点（B13，网关 /api/stores 路由进 store-service，路径不重写）。
 *
 * <p>房间/床位档案查询走 {@code room:view}；建档、床位设维护/恢复走 {@code room:edit}。
 * 床位实时交易态（入住/退房/消毒）一期为前端演示态、不落库；仅维护停用态真实持久化（DESIGN-P4 D1）。
 * 数据域：STORE/SELF 强制本店；SUPER/GROUP/BRAND 可按 storeCode 参查/写，缺省写须显式带店。
 */
@RestController
@RequestMapping("/api/stores")
public class RoomController {

    private final RoomService service;

    public RoomController(RoomService service) {
        this.service = service;
    }

    /** 房间档案（含床位嵌套，床位带 maintStatus/maintReason）。 */
    @GetMapping("/rooms")
    @RequirePerm("room:view")
    public List<Map<String, Object>> listRooms(@RequestParam(value = "storeCode", required = false) String storeCode,
                                               @RequestParam(value = "type", required = false) String type,
                                               @RequestParam(value = "status", required = false) String status) {
        return service.listRooms(resolveStoreCode(storeCode), type, status);
    }

    /** 房间/床位操作日志（最近 limit 条，时间倒序）。 */
    @GetMapping("/rooms/logs")
    @RequirePerm("room:view")
    public List<Map<String, Object>> listLogs(@RequestParam(value = "storeCode", required = false) String storeCode,
                                              @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return service.listLogs(resolveStoreCode(storeCode), limit);
    }

    /** 房间建档：按 roomCode 唯一建房间并批量生成 bedCount 张床位（bed_code={roomCode}-B{n}）。 */
    @PostMapping("/rooms")
    @RequirePerm("room:edit")
    public Map<String, Object> createRoom(@RequestBody CreateRoomCmd cmd) {
        if (cmd == null) throw RoomService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String operator = u == null ? "系统" : u.staffName();
        String storeCode = resolveWriteStoreCode(cmd.storeCode());
        TreatmentRoom r = service.createRoom(storeCode, cmd.roomCode(), cmd.name(), cmd.roomType(),
                cmd.bedCount() == null ? 0 : cmd.bedCount(), operator);
        return Map.of("id", r.getId(), "roomCode", r.getRoomCode(), "storeCode", r.getStoreCode());
    }

    /** 床位设为维护（落 maintStatus=MAINTENANCE + 原因，写日志与审计）。 */
    @PostMapping("/beds/{bedId}/maintenance")
    @RequirePerm("room:edit")
    public Map<String, Object> setMaintenance(@PathVariable("bedId") Long bedId, @RequestBody BedMaintCmd cmd) {
        if (cmd == null) throw RoomService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String operator = u == null ? "系统" : u.staffName();
        service.setBedMaintenance(resolveWriteStoreCode(cmd.storeCode()), bedId, cmd.reason(), operator);
        return Map.of("ok", true);
    }

    /** 床位维护恢复（落 maintStatus=OK，清空原因；前端随后进入消毒演示态）。 */
    @PostMapping("/beds/{bedId}/restore")
    @RequirePerm("room:edit")
    public Map<String, Object> restore(@PathVariable("bedId") Long bedId, @RequestBody BedRestoreCmd cmd) {
        if (cmd == null) throw RoomService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String operator = u == null ? "系统" : u.staffName();
        String requested = cmd.storeCode();
        service.restoreBed(resolveWriteStoreCode(requested), bedId, operator);
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
                throw RoomService.badReq("请指定所属门店");
            }
            return requested.trim();
        }
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            throw RoomService.badReq("当前账号无所属门店，无法操作房间床位");
        }
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode())) {
            throw RoomService.badReq("门店角色仅能操作本店房间床位");
        }
        return u.storeCode();
    }

    /** 房间建档入参；bedCount 缺省由服务端兜底校验（1~50）。 */
    public record CreateRoomCmd(String storeCode, String roomCode, String name, String roomType,
                                Integer bedCount) {}

    /** 床位设维护入参；reason 必填。 */
    public record BedMaintCmd(String storeCode, String reason) {}

    /** 床位维护恢复入参。 */
    public record BedRestoreCmd(String storeCode) {}
}
