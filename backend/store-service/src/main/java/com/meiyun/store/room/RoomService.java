package com.meiyun.store.room;

import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间/床位域服务（B13）：房间建档（建房并批量生成床位）、床位设维护/维护恢复、操作日志查询。
 *
 * <p>边界（DESIGN-P4 D1）：床位实时交易态（FREE/IN_USE/SANITIZING）一期不持久化，
 * 仅维护停用态（{@code maintStatus} OK/MAINTENANCE）落库；入住/退房/消毒为前端演示态（刷新复位）。
 * 床位「设为维护 / 维护恢复」为真实持久化动作，写 room_operation_log 并记 ROOM 审计。
 * 金额无；数据域由 Controller 解析 storeCode，本服务只按店操作。
 */
@Service
public class RoomService {

    private final TreatmentRoomRepository roomRepo;
    private final TreatmentBedRepository bedRepo;
    private final RoomOperationLogRepository logRepo;
    private final ConsumableAuditRecorder audit;

    public RoomService(TreatmentRoomRepository roomRepo,
                       TreatmentBedRepository bedRepo,
                       RoomOperationLogRepository logRepo,
                       ConsumableAuditRecorder audit) {
        this.roomRepo = roomRepo;
        this.bedRepo = bedRepo;
        this.logRepo = logRepo;
        this.audit = audit;
    }

    /** 400 参数错误（中文） */
    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /**
     * 房间视图（含床位嵌套）。出参床位带 maintStatus/maintReason；
     * 前端适配层据 maintStatus 映射四态（MAINTENANCE→维护中，其余→空闲，交易态前端演示）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRooms(String storeCode, String type, String status) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        String tp = isBlank(type) || "ALL".equals(type) ? null : type.trim();
        String st = isBlank(status) || "ALL".equals(status) ? null : status.trim();
        List<TreatmentRoom> rooms = roomRepo.search(sc, tp, st);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TreatmentRoom r : rooms) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("storeCode", r.getStoreCode());
            row.put("roomCode", r.getRoomCode());
            row.put("name", r.getName());
            row.put("roomType", r.getRoomType());
            row.put("status", r.getStatus());
            row.put("remark", r.getRemark());
            List<Map<String, Object>> beds = new ArrayList<>();
            for (TreatmentBed b : bedRepo.findByRoomIdOrderByBedCode(r.getId())) {
                Map<String, Object> bed = new LinkedHashMap<>();
                bed.put("id", b.getId());
                bed.put("roomId", b.getRoomId());
                bed.put("bedCode", b.getBedCode());
                bed.put("maintStatus", b.getMaintStatus());
                bed.put("maintReason", b.getMaintReason());
                beds.add(bed);
            }
            row.put("beds", beds);
            out.add(row);
        }
        return out;
    }

    /** 房间/床位操作日志（最近 limit 条，时间倒序）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLogs(String storeCode, int limit) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        int size = limit <= 0 || limit > 200 ? 50 : limit;
        List<RoomOperationLog> logs = logRepo.search(sc, PageRequest.of(0, size));
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoomOperationLog l : logs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("storeCode", l.getStoreCode());
            row.put("roomCode", l.getRoomCode());
            row.put("bedCode", l.getBedCode());
            row.put("action", l.getAction());
            row.put("text", l.getText());
            row.put("actor", l.getActor());
            row.put("createdAt", l.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** 建房：一店一房间码唯一校验，按 bedCount 批量生成床位（bed_code = {roomCode}-B{n}）。 */
    @Transactional
    public TreatmentRoom createRoom(String storeCode, String roomCode, String name, String roomType,
                                    int bedCount, String operator) {
        if (isBlank(storeCode) || isBlank(roomCode) || isBlank(name) || isBlank(roomType)) {
            throw badReq("门店、房间编号、房间名称、房间类型均不能为空");
        }
        if (bedCount < 1 || bedCount > 50) {
            throw badReq("床位数量须在 1~50 之间");
        }
        if (!List.of("TREATMENT", "CONSULT", "OBSERVE", "RECOVERY").contains(roomType.trim())) {
            throw badReq("房间类型仅支持 TREATMENT/CONSULT/OBSERVE/RECOVERY");
        }
        if (roomRepo.findByStoreCodeAndRoomCode(storeCode, roomCode.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "本店已存在房间「" + roomCode + "」，请勿重复建档");
        }
        TreatmentRoom r = new TreatmentRoom();
        r.setStoreCode(storeCode);
        r.setRoomCode(roomCode.trim());
        r.setName(name.trim());
        r.setRoomType(roomType.trim());
        r.setStatus("ACTIVE");
        r.setCreatedBy(operator);
        r.setUpdatedBy(operator);
        roomRepo.save(r);

        for (int i = 1; i <= bedCount; i++) {
            TreatmentBed b = new TreatmentBed();
            b.setStoreCode(storeCode);
            b.setRoomId(r.getId());
            b.setBedCode(roomCode.trim() + "-B" + i);
            b.setMaintStatus("OK");
            b.setCreatedBy(operator);
            b.setUpdatedBy(operator);
            bedRepo.save(b);
        }

        writeLog(storeCode, r.getRoomCode(), null, "ADD_ROOM",
                "新建房间（" + bedCount + " 张床位）", operator);
        audit.record("ROOM", "ROOM-" + r.getId(), operator, "新建房间",
                String.format("{\"storeCode\":%s,\"roomCode\":%s,\"name\":%s,\"roomType\":%s,\"bedCount\":%d}",
                        jsonStr(storeCode), jsonStr(r.getRoomCode()), jsonStr(r.getName()),
                        jsonStr(r.getRoomType()), bedCount));
        return r;
    }

    /** 床位设为维护：仅 OK（正常）床位可设；落 maintStatus=MAINTENANCE + 原因。 */
    @Transactional
    public void setBedMaintenance(String storeCode, Long bedId, String reason, String operator) {
        if (isBlank(reason)) throw badReq("维护原因不能为空");
        TreatmentBed b = mustFindBed(storeCode, bedId);
        if ("MAINTENANCE".equals(b.getMaintStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "床位「" + b.getBedCode() + "」已处于维护中");
        }
        b.setMaintStatus("MAINTENANCE");
        b.setMaintReason(reason.trim());
        b.setUpdatedBy(operator);
        b.setUpdatedAt(OffsetDateTime.now());
        bedRepo.save(b);
        writeLog(storeCode, roomCodeOf(b), b.getBedCode(), "SET_MAINTENANCE",
                b.getBedCode() + " 设为维护：" + reason.trim(), operator);
        audit.record("ROOM", "BED-" + b.getId(), operator, "床位设维护",
                String.format("{\"storeCode\":%s,\"bedCode\":%s,\"reason\":%s}",
                        jsonStr(storeCode), jsonStr(b.getBedCode()), jsonStr(reason.trim())));
    }

    /** 维护恢复：仅 MAINTENANCE 床位可恢复；落 maintStatus=OK，清空原因（前端随后进入消毒演示态）。 */
    @Transactional
    public void restoreBed(String storeCode, Long bedId, String operator) {
        TreatmentBed b = mustFindBed(storeCode, bedId);
        if (!"MAINTENANCE".equals(b.getMaintStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "床位「" + b.getBedCode() + "」不在维护中，无需恢复");
        }
        b.setMaintStatus("OK");
        b.setMaintReason(null);
        b.setUpdatedBy(operator);
        b.setUpdatedAt(OffsetDateTime.now());
        bedRepo.save(b);
        writeLog(storeCode, roomCodeOf(b), b.getBedCode(), "RESTORE",
                b.getBedCode() + " 维护完成，进入消毒", operator);
        audit.record("ROOM", "BED-" + b.getId(), operator, "床位维护恢复",
                String.format("{\"storeCode\":%s,\"bedCode\":%s}",
                        jsonStr(storeCode), jsonStr(b.getBedCode())));
    }

    // ---- 内部辅助（供 DataInitializer 复用） ----

    /** 建房（种子用，不写审计/日志）：返回已落库房间（含 id）。 */
    @Transactional
    public TreatmentRoom seedRoom(String storeCode, String roomCode, String name, String roomType, String operator) {
        TreatmentRoom r = new TreatmentRoom();
        r.setStoreCode(storeCode);
        r.setRoomCode(roomCode);
        r.setName(name);
        r.setRoomType(roomType);
        r.setStatus("ACTIVE");
        r.setCreatedBy(operator);
        r.setUpdatedBy(operator);
        return roomRepo.save(r);
    }

    /** 建床位（种子用，保留 A01-1 样式床号，可指定初始维护态/原因）。 */
    @Transactional
    public void seedBed(String storeCode, Long roomId, String bedCode, String maintStatus,
                        String maintReason, String operator) {
        TreatmentBed b = new TreatmentBed();
        b.setStoreCode(storeCode);
        b.setRoomId(roomId);
        b.setBedCode(bedCode);
        b.setMaintStatus("MAINTENANCE".equals(maintStatus) ? "MAINTENANCE" : "OK");
        b.setMaintReason(b.getMaintStatus().equals("MAINTENANCE") ? maintReason : null);
        b.setCreatedBy(operator);
        b.setUpdatedBy(operator);
        bedRepo.save(b);
    }

    private TreatmentBed mustFindBed(String storeCode, Long bedId) {
        TreatmentBed b = bedRepo.findById(bedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "床位不存在：" + bedId));
        if (!b.getStoreCode().equals(storeCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本店不存在该床位");
        }
        return b;
    }

    private String roomCodeOf(TreatmentBed b) {
        return roomRepo.findById(b.getRoomId()).map(TreatmentRoom::getRoomCode).orElse(null);
    }

    private void writeLog(String storeCode, String roomCode, String bedCode, String action, String text, String actor) {
        RoomOperationLog l = new RoomOperationLog();
        l.setStoreCode(storeCode);
        l.setRoomCode(roomCode);
        l.setBedCode(bedCode);
        l.setAction(action);
        l.setText(text);
        l.setActor(actor == null || actor.isBlank() ? "系统" : actor);
        logRepo.save(l);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** JSON 字符串转义（审计 payload 手工拼 JSON 时用，与 ConsumableService.jsonStr 同口径） */
    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
