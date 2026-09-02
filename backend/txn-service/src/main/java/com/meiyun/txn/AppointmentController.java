package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.meiyun.txn.audit.AuditRecorder;

/**
 * 预约模块（M4-01）：创建 / 改期 / 取消 / 到店签到 / 未到诊 / 看板 / 反洗客比对。
 *
 * <p>写链路铁律：外键（客户/门店）经服务间调用校验存在性、来源走枚举白名单、重复提交幂等防重、
 * 所有状态流转落审计链；非法入参一律返回中文 4xx，不裸 500。列表读模型富化客户/门店/医生中文名。
 */
@RestController
@RequestMapping("/api/txn/appointment")
public class AppointmentController {

    /** 预约来源白名单（对齐 appointment_source_check）。 */
    private static final Set<String> SOURCES = Set.of("B端登记", "C端小程序", "C端App");
    /** 预约终态/状态白名单（对齐 appointment_status_check）。 */
    private static final String ST_BOOKED = "已预约";
    private static final String ST_ARRIVED = "已到店";
    private static final String ST_NOSHOW = "未到诊";
    private static final String ST_CANCELLED = "已取消";

    private final AppointmentRepository repo;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;

    public AppointmentController(AppointmentRepository repo, AuditRecorder audit, ApptRefNameResolver names) {
        this.repo = repo;
        this.audit = audit;
        this.names = names;
    }

    /** 创建预约（含外键/枚举/幂等校验）。 */
    @PostMapping
    @RequirePerm("appointment:create")
    public AppointmentView create(@RequestBody @Valid CreateCmd cmd) {
        // 来源白名单（缺省 B端登记）
        String source = cmd.source() == null || cmd.source().isBlank() ? "B端登记" : cmd.source();
        if (!SOURCES.contains(source)) {
            throw badRequest("非法预约来源: " + source + "（仅支持 B端登记/C端小程序/C端App）");
        }
        // 时段格式 HH:mm
        if (!cmd.apptTime().matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw badRequest("到店时间格式应为 HH:mm（如 10:30）: " + cmd.apptTime());
        }
        // 外键存在性（服务间调用，不直读别域表）
        if (cmd.customerId() != null && !cmd.customerId().isBlank()
                && !names.customerNames(List.of(cmd.customerId())).containsKey(cmd.customerId())) {
            throw badRequest("客户不存在: " + cmd.customerId());
        }
        if (!names.storeNames(List.of(cmd.storeCode())).containsKey(cmd.storeCode())) {
            throw badRequest("门店不存在: " + cmd.storeCode());
        }
        // 幂等防重：同客户同天同时段已有未取消预约
        if (cmd.customerId() != null && !cmd.customerId().isBlank()
                && repo.existsByCustomerIdAndApptDateAndApptTimeAndStatusNot(
                        cmd.customerId(), cmd.apptDate(), cmd.apptTime(), ST_CANCELLED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该客户在 " + cmd.apptDate() + " " + cmd.apptTime() + " 已有未取消的预约");
        }

        Appointment a = new Appointment();
        a.setApptNo(nextNo());
        a.setCustomerId(cmd.customerId());
        a.setStoreCode(cmd.storeCode());
        a.setProject(cmd.project());
        a.setApptDate(cmd.apptDate());
        a.setApptTime(cmd.apptTime());
        a.setDoctor(cmd.doctor());
        a.setSource(source);
        Appointment saved = repo.save(a);
        String actor = DataScope.currentActor();
        audit.record("APPT", saved.getApptNo(), actor, "CREATE",
                "{\"project\":\"" + cmd.project() + "\",\"date\":\"" + cmd.apptDate()
                        + "\",\"time\":\"" + cmd.apptTime() + "\",\"store\":\"" + cmd.storeCode() + "\"}");
        return toView(saved);
    }

    /** 改期（已预约态才可改）。 */
    @PostMapping("/{no}/reschedule")
    @RequirePerm("appointment:edit")
    public AppointmentView reschedule(@PathVariable String no, @RequestBody @Valid RescheduleCmd cmd) {
        Appointment a = getActive(no);
        if (!cmd.apptTime().matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw badRequest("到店时间格式应为 HH:mm（如 10:30）: " + cmd.apptTime());
        }
        String oldDate = String.valueOf(a.getApptDate()) + " " + a.getApptTime();
        a.setApptDate(cmd.apptDate());
        a.setApptTime(cmd.apptTime());
        Appointment saved = repo.save(a);
        audit.record("APPT", no, DataScope.currentActor(), "RESCHEDULE",
                "{\"from\":\"" + oldDate + "\",\"to\":\"" + cmd.apptDate() + " " + cmd.apptTime() + "\"}");
        return toView(saved);
    }

    /** 取消（已预约态才可取消）。 */
    @PostMapping("/{no}/cancel")
    @RequirePerm("appointment:edit")
    public AppointmentView cancel(@PathVariable String no) {
        Appointment a = getActive(no);
        a.setStatus(ST_CANCELLED);
        Appointment saved = repo.save(a);
        audit.record("APPT", no, DataScope.currentActor(), "CANCEL", "{}");
        return toView(saved);
    }

    /** 到店签到（已预约 → 已到店）。 */
    @PostMapping("/{no}/check-in")
    @RequirePerm("appointment:edit")
    public AppointmentView checkIn(@PathVariable String no) {
        Appointment a = getActive(no);
        a.setStatus(ST_ARRIVED);
        a.setArrivedAt(OffsetDateTime.now());
        Appointment saved = repo.save(a);
        audit.record("APPT", no, DataScope.currentActor(), "CHECK_IN", "{}");
        return toView(saved);
    }

    /** 标记未到诊（已预约 → 未到诊）。 */
    @PostMapping("/{no}/no-show")
    @RequirePerm("appointment:edit")
    public AppointmentView noShow(@PathVariable String no) {
        Appointment a = getActive(no);
        a.setStatus(ST_NOSHOW);
        Appointment saved = repo.save(a);
        audit.record("APPT", no, DataScope.currentActor(), "NO_SHOW", "{}");
        return toView(saved);
    }

    /** 列表（按日期/门店筛选，富化中文名）。数据域强制注入：SELF 医生只见本人预约、STORE 只见本店、REGION 见本区、GROUP 全量。 */
    @GetMapping
    @RequirePerm("appointment:view")
    public List<AppointmentView> list(@RequestParam(required = false) String storeCode,
                                      @RequestParam(required = false) LocalDate date) {
        Specification<Appointment> spec = DataScope.ownedSpec("storeCode", "doctor");
        if (date != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("apptDate"), date));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        List<Appointment> list = repo.findAll(spec, Sort.by(
                Sort.Order.asc("apptDate"), Sort.Order.asc("storeCode"), Sort.Order.asc("apptTime")));
        return toViews(list);
    }

    /** 看板：各状态数量 + 到店率。数据域强制注入：与列表同规格（SELF 医生只见本人、STORE 本店、REGION 本区、GROUP 全量），显式传他店码 404 不泄露。 */
    @GetMapping("/board")
    @RequirePerm("appointment:view")
    public Map<String, Object> board(@RequestParam(required = false) LocalDate date,
                                     @RequestParam(required = false) String storeCode) {
        if (storeCode != null && !storeCode.isBlank() && !DataScope.canReadStore(storeCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        Specification<Appointment> spec = DataScope.ownedSpec("storeCode", "doctor");
        if (date != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("apptDate"), date));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        long total = 0, arrived = 0, noShow = 0, cancelled = 0, booked = 0;
        for (Appointment a : repo.findAll(spec)) {
            switch (a.getStatus()) {
                case ST_BOOKED -> booked++;
                case ST_ARRIVED -> arrived++;
                case ST_NOSHOW -> noShow++;
                case ST_CANCELLED -> cancelled++;
            }
            total++;
        }
        long effective = total - cancelled;   // 到店率分母剔除已取消
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("booked", booked);
        m.put("arrived", arrived);
        m.put("noShow", noShow);
        m.put("cancelled", cancelled);
        m.put("arrivalRate", effective == 0 ? 0.0 : Math.round(arrived * 1000.0 / effective) / 1000.0);
        return m;
    }

    /** 反洗客比对（M4-04）：同一客户近 30 天跨门店记录（服务端按数据域过滤，越权门店记录不可见）。 */
    @GetMapping("/cross-check/{customerId}")
    @RequirePerm("appointment:view")
    public List<AppointmentView> crossCheck(@PathVariable String customerId) {
        LocalDate since = LocalDate.now().minusDays(30);
        Specification<Appointment> spec = DataScope.<Appointment>ownedSpec("storeCode", "doctor")
                .and((root, q, cb) -> cb.equal(root.get("customerId"), customerId))
                .and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("apptDate"), since));
        return toViews(repo.findAll(spec, Sort.by(Sort.Order.desc("apptDate"))));
    }

    // ---- 内部 ----

    private Appointment getActive(String no) {
        Appointment a = repo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        // 详情数据域：门店归属校验（改期/取消/签到/未到诊共用此入口，一处覆盖）
        if (!DataScope.canReadStore(a.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        if (!ST_BOOKED.equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预约当前状态为「" + a.getStatus() + "」，不可再操作");
        }
        return a;
    }

    /** 生成当日不重号预约号：AP + yyyyMMdd + - + 6 位序号（取当日最大序号 +1）。 */
    private synchronized String nextNo() {
        String day = LocalDate.now().toString().replace("-", "");
        String prefix = "AP" + day + "-%";
        long seq = repo.maxSeqOfDay(prefix) + 1;
        return "AP" + day + "-" + String.format("%06d", seq);
    }

    private List<AppointmentView> toViews(List<Appointment> list) {
        if (list.isEmpty()) return new ArrayList<>();
        Map<String, String> custNames = names.customerNames(list.stream().map(Appointment::getCustomerId).toList());
        Map<String, String> storeNames = names.storeNames(list.stream().map(Appointment::getStoreCode).toList());
        Map<String, String> doctorNames = names.staffNames(list.stream().map(Appointment::getDoctor).toList());
        List<AppointmentView> out = new ArrayList<>(list.size());
        for (Appointment a : list) {
            out.add(new AppointmentView(
                    a.getApptNo(), a.getCustomerId(),
                    a.getCustomerId() == null ? null : custNames.get(a.getCustomerId()),
                    a.getStoreCode(), storeNames.get(a.getStoreCode()),
                    a.getProject(), a.getApptDate(), a.getApptTime(),
                    a.getDoctor(), a.getDoctor() == null ? null : doctorNames.get(a.getDoctor()),
                    a.getSource(), a.getStatus(), a.getArrivedAt(), a.getCreatedAt()));
        }
        return out;
    }

    private AppointmentView toView(Appointment a) {
        return toViews(List.of(a)).get(0);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /** 预约读模型：在实体字段上冗余客户/门店/医生中文名（零英文技术码外露）。 */
    public record AppointmentView(
            String apptNo, String customerId, String customerName,
            String storeCode, String storeName,
            String project, LocalDate apptDate, String apptTime,
            String doctor, String doctorName,
            String source, String status,
            OffsetDateTime arrivedAt, OffsetDateTime createdAt) {}

    public record CreateCmd(
            String customerId,
            @NotBlank String storeCode,
            @NotBlank String project,
            @NotNull LocalDate apptDate,
            @NotBlank String apptTime,
            String doctor,
            String source,
            String operator) {}

    public record RescheduleCmd(@NotNull LocalDate apptDate, @NotBlank String apptTime) {}
}
