package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调度中心聚合服务（P5-B49 卡12，M1 调度中心）。
 *
 * <p>资源三源：DOCTOR 医生复用 {@link OrgStaffClient#listStaffByRole}（org 不可用软降级空列表）；
 * ROOM 治疗室经 {@link StoreRoomClient} 取 store 内部端点（store 不可用软降级空列表）；
 * DEVICE 设备经 {@link StoreEquipmentClient} 取 store 内部端点（仅 NORMAL 态，软降级空列表；
 * P5-B51 卡4 接真，resourceId=资产编号 assetNo）。
 *
 * <p>待派单 Job = 当日「已预约/已到店」且无派单占用（含 DONE 终态）的预约；已到店排前、到店时间升序。
 * 派单 start 锚定预约 apptTime（不可自由选时），时长固定 60 分钟（project↔SKU 名匹配率 0% 实证，
 * 不伪造真实时长，Backlog）；DOCTOR 资源必须与预约指定医生一致；同资源同日时段重叠拒绝；
 * 占用状态由预约态派生（已预约=SCHEDULED / 已到店=IN_PROGRESS）。释放为 RELEASED 保留行；
 * 治疗完成由方案单 treatDone AFTER_COMMIT 联动置 DONE（终态回显不占时段，不可释放/再派单）；
 * 预约改期由 {@link #followReschedule} 同事务跟随移动 SCHEDULED 派单（P5-B51 卡5，锚定语义延伸，
 * 新时段越出班次或与同资源活跃占用冲突则拒绝改期；已到店预约不可改期，IN_PROGRESS 不会进入跟随）。
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 活跃占用状态（已排期 + 进行中）；RELEASED 不再占用时段，DONE 终态不占时段。 */
    private static final List<String> ACTIVE = List.of(
            DispatchAssignment.ST_SCHEDULED, DispatchAssignment.ST_IN_PROGRESS);
    /** 读侧可见状态（活跃 + 已完成回显）：时间轴渲染 + 待派单排除集（治完预约不重现）。 */
    private static final List<String> VISIBLE = List.of(
            DispatchAssignment.ST_SCHEDULED, DispatchAssignment.ST_IN_PROGRESS, DispatchAssignment.ST_DONE);
    /** 可派单预约态。 */
    private static final String ST_BOOKED = "已预约";
    private static final String ST_ARRIVED = "已到店";
    /** 固定班次（暂无真实排班源，Backlog）。 */
    public static final String WORK_START = "09:00";
    public static final String WORK_END = "20:00";
    /** 固定派单时长（分钟）。 */
    public static final int DURATION_MIN = 60;

    private final DispatchAssignmentRepository assignmentRepo;
    private final AppointmentRepository appointmentRepo;
    private final OrgStaffClient orgStaffClient;
    private final StoreRoomClient storeRoomClient;
    private final StoreEquipmentClient storeEquipmentClient;
    private final ApptRefNameResolver names;
    private final AuditRecorder audit;

    public DispatchService(DispatchAssignmentRepository assignmentRepo,
                           AppointmentRepository appointmentRepo,
                           OrgStaffClient orgStaffClient,
                           StoreRoomClient storeRoomClient,
                           StoreEquipmentClient storeEquipmentClient,
                           ApptRefNameResolver names,
                           AuditRecorder audit) {
        this.assignmentRepo = assignmentRepo;
        this.appointmentRepo = appointmentRepo;
        this.orgStaffClient = orgStaffClient;
        this.storeRoomClient = storeRoomClient;
        this.storeEquipmentClient = storeEquipmentClient;
        this.names = names;
        this.audit = audit;
    }

    // ============================= 读侧 =============================

    /** 资源列表（含当日占用块：活跃 + 已完成 DONE 回显）。type 为空返回全部三类；DEVICE 仅 NORMAL 态设备。 */
    @Transactional(readOnly = true)
    public List<ResourceView> resources(String storeCode, String type, LocalDate date) {
        String sc = requireStore(storeCode);
        LocalDate day = date == null ? LocalDate.now() : date;
        String wantType = type == null || type.isBlank() ? null : type.trim();
        List<DispatchAssignment> active =
                assignmentRepo.findByStoreCodeAndBizDateAndStatusInOrderByStartTimeAsc(sc, day, VISIBLE);

        List<ResourceView> out = new ArrayList<>();
        if (wantType == null || DispatchAssignment.RES_DOCTOR.equals(wantType)) {
            for (OrgStaffClient.StaffBrief s : orgStaffClient.listStaffByRole("DOCTOR", sc, null)) {
                List<AssignmentView> blocks = toViews(active.stream()
                        .filter(a -> DispatchAssignment.RES_DOCTOR.equals(a.getResourceType())
                                && s.staffId().equals(a.getResourceId()))
                        .toList());
                out.add(new ResourceView(s.staffId(), DispatchAssignment.RES_DOCTOR,
                        s.staffName(), null, null, WORK_START, WORK_END, "ON", blocks));
            }
        }
        if (wantType == null || DispatchAssignment.RES_ROOM.equals(wantType)) {
            for (Map<String, Object> r : storeRoomClient.listTreatmentRooms(sc)) {
                String roomCode = str(r.get("roomCode"));
                if (roomCode.isBlank()) continue;
                String roomName = str(r.get("name"));
                List<AssignmentView> blocks = toViews(active.stream()
                        .filter(a -> DispatchAssignment.RES_ROOM.equals(a.getResourceType())
                                && roomCode.equals(a.getResourceId()))
                        .toList());
                out.add(new ResourceView(roomCode, DispatchAssignment.RES_ROOM,
                        roomName.isBlank() ? roomCode : roomName, null, null,
                        WORK_START, WORK_END, "ON", blocks));
            }
        }
        if (wantType == null || DispatchAssignment.RES_DEVICE.equals(wantType)) {
            for (Map<String, Object> d : storeEquipmentClient.listNormalEquipments(sc)) {
                String assetNo = str(d.get("assetNo"));
                if (assetNo.isBlank()) continue;
                String devName = str(d.get("name"));
                List<AssignmentView> blocks = toViews(active.stream()
                        .filter(a -> DispatchAssignment.RES_DEVICE.equals(a.getResourceType())
                                && assetNo.equals(a.getResourceId()))
                        .toList());
                out.add(new ResourceView(assetNo, DispatchAssignment.RES_DEVICE,
                        devName.isBlank() ? assetNo : devName, null, null,
                        WORK_START, WORK_END, "ON", blocks));
            }
        }
        return out;
    }

    /** 待派单工单列表：当日已预约/已到店且无活跃占用，已到店排前 + apptTime 升序。 */
    @Transactional(readOnly = true)
    public List<JobView> jobs(String storeCode, LocalDate date) {
        String sc = requireStore(storeCode);
        LocalDate day = date == null ? LocalDate.now() : date;
        List<Appointment> appts = appointmentRepo.findByStoreCodeAndApptDateOrderByApptTimeAsc(sc, day);

        // 排除集含 DONE：治完预约的 appointment.status 不变，不把已完成工单重新暴露为待派单
        List<DispatchAssignment> active =
                assignmentRepo.findByStoreCodeAndBizDateAndStatusInOrderByStartTimeAsc(sc, day, VISIBLE);
        java.util.Set<String> assignedApptNos = active.stream()
                .map(DispatchAssignment::getApptNo).collect(java.util.stream.Collectors.toSet());
        List<Appointment> pending = appts.stream()
                .filter(a -> ST_BOOKED.equals(a.getStatus()) || ST_ARRIVED.equals(a.getStatus()))
                .filter(a -> !assignedApptNos.contains(a.getApptNo()))
                .sorted(Comparator
                        .comparing((Appointment a) -> ST_ARRIVED.equals(a.getStatus()) ? 0 : 1)
                        .thenComparing(Appointment::getApptTime))
                .toList();
        if (pending.isEmpty()) return new ArrayList<>();

        Map<String, String> custNames = names.customerNames(pending.stream().map(Appointment::getCustomerId).toList());
        Map<String, String> doctorNames = names.staffNames(pending.stream().map(Appointment::getDoctor).toList());
        List<JobView> out = new ArrayList<>(pending.size());
        for (Appointment a : pending) {
            String customerName = a.getCustomerId() == null ? null : custNames.get(a.getCustomerId());
            String preferredDoctor = a.getDoctor() == null ? null : doctorNames.get(a.getDoctor());
            out.add(new JobView(
                    a.getApptNo(), a.getApptNo(),
                    customerName != null ? customerName : "未登记客户",
                    a.getProject(), DURATION_MIN, a.getApptTime(),
                    a.getDoctor(), preferredDoctor,
                    "NORMAL", "PENDING", ST_ARRIVED.equals(a.getStatus()),
                    String.valueOf(a.getCreatedAt())));
        }
        return out;
    }

    // ============================= 写侧 =============================

    /** 派单：start 锚定预约 apptTime，时长 60 分钟，全量校验 + 状态派生 + 审计；DONE 终态预约 422。 */
    @Transactional
    public AssignmentView dispatch(String storeCode, DispatchCmd cmd) {
        String sc = requireStore(storeCode);
        if (cmd == null || cmd.apptNo() == null || cmd.apptNo().isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "请选择要派单的预约");
        }
        String resourceType = cmd.resourceType() == null ? "" : cmd.resourceType().trim();
        if (!DispatchAssignment.RES_DOCTOR.equals(resourceType)
                && !DispatchAssignment.RES_ROOM.equals(resourceType)
                && !DispatchAssignment.RES_DEVICE.equals(resourceType)) {
            throw error(HttpStatus.BAD_REQUEST, "资源类型仅支持医生、治疗室或设备");
        }
        String resourceId = cmd.resourceId() == null ? "" : cmd.resourceId().trim();
        if (resourceId.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "请选择派单资源");
        }
        Appointment a = appointmentRepo.findById(cmd.apptNo()).orElse(null);
        if (a == null || !DataScope.canReadStore(a.getStoreCode())) {
            throw error(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        if (!sc.equals(a.getStoreCode())) {
            throw error(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        if (!ST_BOOKED.equals(a.getStatus()) && !ST_ARRIVED.equals(a.getStatus())) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "预约当前状态为「" + a.getStatus() + "」，不可派单");
        }
        if (assignmentRepo.existsByApptNoAndStatusIn(a.getApptNo(), List.of(DispatchAssignment.ST_DONE))) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "该预约已完成治疗，不可再派单");
        }
        if (assignmentRepo.existsByApptNoAndStatusIn(a.getApptNo(), ACTIVE)) {
            throw error(HttpStatus.CONFLICT, "该预约已派单，如需改派请先释放原派单");
        }

        // 资源名校验：医生必须等于预约指定医生；治疗室须在本店在用治疗室清单内；设备须在本店 NORMAL 态清单内。
        String resourceName;
        if (DispatchAssignment.RES_DOCTOR.equals(resourceType)) {
            if (a.getDoctor() == null || a.getDoctor().isBlank()) {
                throw error(HttpStatus.UNPROCESSABLE_ENTITY, "该预约未指定医生，无法派给医生资源");
            }
            if (!resourceId.equals(a.getDoctor())) {
                Map<String, String> dn = names.staffNames(List.of(a.getDoctor()));
                String docName = dn.getOrDefault(a.getDoctor(), a.getDoctor());
                throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                        "该预约已指定医生 " + docName + "，如需改派请先在预约管理调整预约");
            }
            resourceName = orgStaffClient.listStaffByRole("DOCTOR", sc, null).stream()
                    .filter(s -> resourceId.equals(s.staffId())).findFirst()
                    .map(OrgStaffClient.StaffBrief::staffName).orElse(null);
            if (resourceName == null) {
                throw error(HttpStatus.NOT_FOUND, "所选医生不存在或已不在职");
            }
        } else if (DispatchAssignment.RES_ROOM.equals(resourceType)) {
            Map<String, Object> room = storeRoomClient.listTreatmentRooms(sc).stream()
                    .filter(r -> resourceId.equals(str(r.get("roomCode"))))
                    .findFirst().orElse(null);
            if (room == null) {
                throw error(HttpStatus.NOT_FOUND, "所选治疗室不存在或已停用");
            }
            resourceName = str(room.get("name"));
            if (resourceName.isBlank()) resourceName = resourceId;
        } else {
            Map<String, Object> device = storeEquipmentClient.listNormalEquipments(sc).stream()
                    .filter(d -> resourceId.equals(str(d.get("assetNo"))))
                    .findFirst().orElse(null);
            if (device == null) {
                throw error(HttpStatus.NOT_FOUND, "所选设备不存在或不可用（仅正常状态设备可派单）");
            }
            resourceName = str(device.get("name"));
            if (resourceName.isBlank()) resourceName = resourceId;
        }

        // start 锚定预约时段；固定班次 + 固定时长校验。
        String start = a.getApptTime();
        if (start.compareTo(WORK_START) < 0 || plusMinutes(start, DURATION_MIN).compareTo(WORK_END) > 0) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "派单时段 " + start + " 超出班次 " + WORK_START + "-" + WORK_END
                            + "，改期请先在预约管理改期");
        }
        String end = plusMinutes(start, DURATION_MIN);

        // 同资源同日时段重叠（HH:mm 零填充字符串可直接按区间比较）。
        List<DispatchAssignment> own = assignmentRepo
                .findByStoreCodeAndBizDateAndResourceTypeAndResourceIdAndStatusIn(
                        sc, a.getApptDate(), resourceType, resourceId, ACTIVE);
        for (DispatchAssignment ex : own) {
            if (start.compareTo(ex.getEndTime()) < 0 && ex.getStartTime().compareTo(end) < 0) {
                throw error(HttpStatus.CONFLICT,
                        resourceName + " 在 " + ex.getStartTime() + "-" + ex.getEndTime() + " 已有排单，请选其他时段或资源");
            }
        }

        Map<String, String> custNames = names.customerNames(
                a.getCustomerId() == null ? List.of() : List.of(a.getCustomerId()));
        DispatchAssignment asg = new DispatchAssignment();
        asg.setStoreCode(sc);
        asg.setBizDate(a.getApptDate());
        asg.setResourceType(resourceType);
        asg.setResourceId(resourceId);
        asg.setResourceName(resourceName);
        asg.setApptNo(a.getApptNo());
        asg.setCustomerName(custNames.getOrDefault(a.getCustomerId(), "未登记客户"));
        asg.setItemName(a.getProject());
        asg.setStartTime(start);
        asg.setEndTime(end);
        asg.setStatus(ST_ARRIVED.equals(a.getStatus())
                ? DispatchAssignment.ST_IN_PROGRESS : DispatchAssignment.ST_SCHEDULED);
        DispatchAssignment saved = assignmentRepo.save(asg);

        audit.record("DISPATCH", String.valueOf(saved.getId()), DataScope.currentActor(), "DISPATCH",
                jsonPayload(saved));
        return toView(saved);
    }

    /**
     * 改期跟随（P5-B51 卡5）：预约改期时同事务把其 SCHEDULED 派单移动到新日期时段
     * （派单 start 锚定 apptTime 的既有语义延伸，跨日改期 bizDate 一并跟随）。
     * 新时段越出固定班次窗 → 422；与同资源活跃占用重叠（排除自身）→ 409；
     * 任一校验失败抛异常，由调用方事务整体回滚，预约与派单不产生中间态。
     * 每条跟随的派单记 audit RESCHEDULE_FOLLOW（调度域动作调度域审计）。
     */
    @Transactional
    public void followReschedule(Appointment a, LocalDate newDate, String newTime) {
        List<DispatchAssignment> scheduled = assignmentRepo.findByApptNoAndStatusIn(
                a.getApptNo(), List.of(DispatchAssignment.ST_SCHEDULED));
        if (scheduled.isEmpty()) return;
        if (newTime.compareTo(WORK_START) < 0 || plusMinutes(newTime, DURATION_MIN).compareTo(WORK_END) > 0) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "该预约已派单，新时段 " + newTime + " 超出班次 " + WORK_START + "-" + WORK_END
                            + "，请先释放派单再改期");
        }
        String end = plusMinutes(newTime, DURATION_MIN);
        for (DispatchAssignment asg : scheduled) {
            List<DispatchAssignment> own = assignmentRepo
                    .findByStoreCodeAndBizDateAndResourceTypeAndResourceIdAndStatusIn(
                            asg.getStoreCode(), newDate, asg.getResourceType(), asg.getResourceId(), ACTIVE);
            for (DispatchAssignment ex : own) {
                if (ex.getId().equals(asg.getId())) continue;
                if (newTime.compareTo(ex.getEndTime()) < 0 && ex.getStartTime().compareTo(end) < 0) {
                    throw error(HttpStatus.CONFLICT,
                            asg.getResourceName() + " 在新时段 " + newTime + "-" + end
                                    + " 与已有排单 " + ex.getStartTime() + "-" + ex.getEndTime()
                                    + " 冲突，请先释放派单或改到其他时段");
                }
            }
            asg.setBizDate(newDate);
            asg.setStartTime(newTime);
            asg.setEndTime(end);
            DispatchAssignment saved = assignmentRepo.save(asg);
            audit.record("DISPATCH", String.valueOf(saved.getId()), DataScope.currentActor(),
                    "RESCHEDULE_FOLLOW", jsonPayload(saved));
            log.info("改期跟随：派单 id={} 预约 {} 移至 {} {}-{}", saved.getId(), a.getApptNo(), newDate, newTime, end);
        }
    }

    /** 释放派单：仅活跃态可释放 → RELEASED + released_at 保留行；重复释放/DONE 终态 422。 */
    @Transactional
    public AssignmentView release(Long id, String storeCode) {
        DispatchAssignment asg = assignmentRepo.findById(id).orElse(null);
        if (asg == null || !DataScope.canReadStore(asg.getStoreCode())) {
            throw error(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        if (storeCode != null && !storeCode.isBlank() && !storeCode.trim().equals(asg.getStoreCode())) {
            throw error(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        if (DispatchAssignment.ST_RELEASED.equals(asg.getStatus())) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "该派单已释放，请勿重复操作");
        }
        if (DispatchAssignment.ST_DONE.equals(asg.getStatus())) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "该派单已完成治疗，终态无需释放");
        }
        asg.setStatus(DispatchAssignment.ST_RELEASED);
        asg.setReleasedAt(OffsetDateTime.now());
        DispatchAssignment saved = assignmentRepo.save(asg);
        audit.record("DISPATCH", String.valueOf(saved.getId()), DataScope.currentActor(), "RELEASE",
                jsonPayload(saved));
        return toView(saved);
    }

    // ============================= 内部 =============================

    private String requireStore(String storeCode) {
        if (storeCode == null || storeCode.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "请选择门店");
        }
        if (!DataScope.canReadStore(storeCode.trim())) {
            throw error(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return storeCode.trim();
    }

    private List<AssignmentView> toViews(List<DispatchAssignment> list) {
        List<AssignmentView> out = new ArrayList<>(list.size());
        for (DispatchAssignment a : list) out.add(toView(a));
        return out;
    }

    private AssignmentView toView(DispatchAssignment a) {
        return new AssignmentView(String.valueOf(a.getId()), a.getResourceType(), a.getResourceId(),
                a.getResourceName(), a.getApptNo(), a.getCustomerName(), a.getItemName(),
                a.getStartTime(), a.getEndTime(), a.getStatus());
    }

    private String jsonPayload(DispatchAssignment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("storeCode", a.getStoreCode());
        m.put("bizDate", String.valueOf(a.getBizDate()));
        m.put("resourceType", a.getResourceType());
        m.put("resourceId", a.getResourceId());
        m.put("resourceName", a.getResourceName());
        m.put("apptNo", a.getApptNo());
        m.put("start", a.getStartTime());
        m.put("end", a.getEndTime());
        m.put("status", a.getStatus());
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("调度审计 payload 序列化失败，回落空 JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private static String plusMinutes(String hhmm, int minutes) {
        String[] hm = hhmm.split(":");
        int total = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]) + minutes;
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static ResponseStatusException error(HttpStatus status, String msg) {
        return new ResponseStatusException(status, msg);
    }

    // ============================= 读模型 DTO =============================

    /** 资源读模型：内嵌当日活跃占用块。title/room 无真实源恒为 null（前端兜底不显示）。 */
    public record ResourceView(
            String id, String type, String name, String title, String room,
            String workStart, String workEnd, String status,
            List<AssignmentView> assignments) {}

    /** 待派单读模型：preferredDoctor 为预约指定医生中文名；arrived 派生自预约态用于排序/徽标。 */
    public record JobView(
            String id, String jobNo, String customerName, String itemName, int durationMin,
            String apptTime, String preferredDoctorId, String preferredDoctor,
            String priority, String status, boolean arrived, String createdAt) {}

    /** 派单占用块读模型。 */
    public record AssignmentView(
            String id, String resourceType, String resourceId, String resourceName,
            String jobId, String customerName, String itemName,
            String start, String end, String status) {}

    /** 派单命令。 */
    public record DispatchCmd(String apptNo, String resourceType, String resourceId) {}
}
