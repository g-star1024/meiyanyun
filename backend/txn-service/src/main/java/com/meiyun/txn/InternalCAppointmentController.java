package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * C 端预约 internal 端点（DESIGN-C §四端点 #6 落库通道；铁律 0 订正：预约域归属 txn 服务，
 * 契约占位「customer 域」与真实库不符，随 C-B3 拍回填订正）。
 *
 * <p>仅供 c-service 经 X-Internal-Token 系统身份调用（网关 withInternalGuard 对 /api/*​/internal/**
 * 外部 404 隐身，网关零改动）。创建/取消全量复用 {@link AppointmentController} 同源硬口径：
 * 来源白名单、HH:mm 时段、客户/门店外键存在性、同人同天同时段幂等 409、预约号
 * AP+yyyyMMdd+-+6 位当日序号、状态机仅「已预约」可取消、全链审计落 APPT。
 *
 * <p>与 B 端差异：①B 端数据域（DataScope.canReadStore/ownedSpec）在此不适用——C 端行级隔离
 * 由 c-service 侧 customer_id 归属校验负责（越权 403 中文）；②customerId 强制非空（C 端
 * 预约必绑会员档案）；③skuCode 不支持（C 端契约无此字段，非空即拒，不绕过 B 端 ACTIVE 硬口径）；
 * ④审计 actor 固定 "c-service"（溯源通道）。
 */
@RestController
@RequestMapping("/api/txn/internal/c-appointments")
public class InternalCAppointmentController {

    /** 预约来源白名单（对齐 appointment_source_check，与 B 端同口径）。 */
    private static final Set<String> SOURCES = Set.of("B端登记", "C端小程序", "C端App");
    private static final String ST_BOOKED = "已预约";
    private static final String ST_CANCELLED = "已取消";

    private final AppointmentRepository repo;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;

    public InternalCAppointmentController(AppointmentRepository repo, AuditRecorder audit,
                                          ApptRefNameResolver names) {
        this.repo = repo;
        this.audit = audit;
        this.names = names;
    }

    /** C 端创建预约（与 B 端 create 同硬口径；source 缺省 C端小程序）。 */
    @PostMapping
    @RequirePerm("internal:c-appointment")
    public AppointmentController.AppointmentView create(@RequestBody @Valid AppointmentController.CreateCmd cmd) {
        String source = cmd.source() == null || cmd.source().isBlank() ? "C端小程序" : cmd.source();
        if (!SOURCES.contains(source)) {
            throw badRequest("非法预约来源: " + source + "（仅支持 B端登记/C端小程序/C端App）");
        }
        if (!cmd.apptTime().matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw badRequest("到店时间格式应为 HH:mm（如 10:30）: " + cmd.apptTime());
        }
        if (cmd.customerId() == null || cmd.customerId().isBlank()) {
            throw badRequest("客户编号不能为空");
        }
        if (!names.customerNames(List.of(cmd.customerId())).containsKey(cmd.customerId())) {
            throw badRequest("客户不存在: " + cmd.customerId());
        }
        if (!names.storeNames(List.of(cmd.storeCode())).containsKey(cmd.storeCode())) {
            throw badRequest("门店不存在: " + cmd.storeCode());
        }
        // 幂等防重：同客户同天同时段已有未取消预约（与 B 端同口径 409）
        if (repo.existsByCustomerIdAndApptDateAndApptTimeAndStatusNot(
                cmd.customerId(), cmd.apptDate(), cmd.apptTime(), ST_CANCELLED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该客户在 " + cmd.apptDate() + " " + cmd.apptTime() + " 已有未取消的预约");
        }
        if (cmd.skuCode() != null && !cmd.skuCode().isBlank()) {
            throw badRequest("C 端预约暂不支持绑定标准项目");
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
        audit.record("APPT", saved.getApptNo(), "c-service", "CREATE",
                "{\"project\":\"" + cmd.project() + "\",\"date\":\"" + cmd.apptDate()
                        + "\",\"time\":\"" + cmd.apptTime() + "\",\"store\":\"" + cmd.storeCode()
                        + "\",\"channel\":\"C\"}");
        return toView(saved);
    }

    /** C 端取消预约（与 B 端 cancel 同状态机：仅「已预约」可取消；归属校验在 c-service 侧）。 */
    @PostMapping("/{no}/cancel")
    @RequirePerm("internal:c-appointment")
    public AppointmentController.AppointmentView cancel(@PathVariable String no) {
        Appointment a = repo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!ST_BOOKED.equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "预约当前状态为「" + a.getStatus() + "」，不可再操作");
        }
        a.setStatus(ST_CANCELLED);
        Appointment saved = repo.save(a);
        audit.record("APPT", no, "c-service", "CANCEL", "{\"channel\":\"C\"}");
        return toView(saved);
    }

    /** 生成当日不重号预约号：AP + yyyyMMdd + - + 6 位序号（与 B 端同算法同序列）。 */
    private synchronized String nextNo() {
        String day = LocalDate.now().toString().replace("-", "");
        String prefix = "AP" + day + "-%";
        long seq = repo.maxSeqOfDay(prefix) + 1;
        return "AP" + day + "-" + String.format("%06d", seq);
    }

    private AppointmentController.AppointmentView toView(Appointment a) {
        String custName = a.getCustomerId() == null ? null
                : names.customerNames(List.of(a.getCustomerId())).get(a.getCustomerId());
        String storeName = names.storeNames(List.of(a.getStoreCode())).get(a.getStoreCode());
        String doctorName = a.getDoctor() == null ? null
                : names.staffNames(List.of(a.getDoctor())).get(a.getDoctor());
        return new AppointmentController.AppointmentView(
                a.getApptNo(), a.getCustomerId(), custName,
                a.getStoreCode(), storeName,
                a.getProject(), a.getSkuCode(), a.getApptDate(), a.getApptTime(),
                a.getDoctor(), doctorName,
                a.getSource(), a.getStatus(), a.getArrivedAt(), a.getCreatedAt());
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
