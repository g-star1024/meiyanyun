package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 预约到店共享编排（P5-B29 卡③）：预约状态「已预约 → 已到店」+ 审计 + M2 待划扣任务 + 接待队列登记
 * 四步同事务（同生共死）。
 *
 * <p>两个入口复用同一编排，保证口径一致且天然防双到店：
 * <ol>
 *   <li>预约看板手工签到：{@link AppointmentController#checkIn}（预约须为「已预约」，前置校验已做）；</li>
 *   <li>M2-09 签到台「预约到店」登记：手机号锚定客户后命中本店当日「已预约」单自动勾连，
 *       未命中维持纯到店登记，不强校验以免阻断真实到店。</li>
 * </ol>
 * 预约一旦置「已到店」，看板路由因状态非「已预约」被拒、签到台路由只匹配「已预约」单，两路均不可重复触发。
 */
@Service
public class AppointmentArrivalService {

    public static final String ST_BOOKED = "已预约";
    public static final String ST_ARRIVED = "已到店";

    private final AppointmentRepository apptRepo;
    private final AuditRecorder audit;
    private final WriteoffDeskService writeoffDeskService;
    private final ArrivalService arrivalService;

    public AppointmentArrivalService(AppointmentRepository apptRepo, AuditRecorder audit,
                                     WriteoffDeskService writeoffDeskService,
                                     ArrivalService arrivalService) {
        this.apptRepo = apptRepo;
        this.audit = audit;
        this.writeoffDeskService = writeoffDeskService;
        this.arrivalService = arrivalService;
    }

    /**
     * 预约到店编排：调用方须已完成存在性/数据域/「已预约」状态校验。同事务置到店、审计 CHECK_IN、
     * 建待划扣任务（同预约号幂等）、建接待队列（apptNo 幂等）。
     */
    @Transactional
    public ArrivalResult checkIn(Appointment appt) {
        String actor = DataScope.currentActor();
        appt.setStatus(ST_ARRIVED);
        appt.setArrivedAt(OffsetDateTime.now());
        Appointment saved = apptRepo.save(appt);
        audit.record("APPT", saved.getApptNo(), actor, "CHECK_IN", "{}");
        WriteoffDeskTask wd = writeoffDeskService.createFromAppointment(saved);
        arrivalService.createFromAppointment(saved);
        return new ArrivalResult(saved, wd == null ? null : wd.getWdNo());
    }

    /** 编排结果：到店预约单 + 同事务生成的待划扣任务号（无卡/无客户等场景服务内仍可能建单，wdNo 一般非空）。 */
    public record ArrivalResult(Appointment appointment, String wdNo) {}
}
