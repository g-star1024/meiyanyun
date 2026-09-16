package com.meiyun.org;

import com.meiyun.org.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 班次写入共享件（B54 卡5）：把单日 upsert 与「按上一周复制」从 Controller 抽出，
 * 供单日改班、周例复制、请假批准联动三处复用同一套「存在即跳过/异码覆盖」与审计口径。
 *
 * <p>不处理参数与数据域校验（调用方负责），只负责 staff_shift 落库与 SCHEDULE 审计。
 */
@Component
public class ShiftUpsertService {

    private final StaffRepository staffRepo;
    private final StaffShiftRepository shiftRepo;
    private final AuditRecorder audit;

    public ShiftUpsertService(StaffRepository staffRepo, StaffShiftRepository shiftRepo, AuditRecorder audit) {
        this.staffRepo = staffRepo;
        this.shiftRepo = shiftRepo;
        this.audit = audit;
    }

    /**
     * 区间每日覆盖为 LEAVE + OVERRIDE（请假批准联动）。与单日改班不同：批准是权威动作，
     * 无论既有班次（TEMPLATE 铺底或手工 OVERRIDE）一律覆盖，保证请假期间不可派单；
     * 已是 LEAVE 的日期幂等跳过、不重复审计。返回实际改动天数。
     */
    @Transactional
    public int applyLeaveOverride(String staffId, String staffName, LocalDate start, LocalDate end,
                                  String leaveNo, String actor) {
        int changed = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            StaffShift sh = shiftRepo.findByStaffIdAndShiftDate(staffId, d).orElse(null);
            if (sh != null && "LEAVE".equals(sh.getShiftCode())) {
                continue;
            }
            if (sh == null) {
                sh = new StaffShift();
                sh.setStaffId(staffId);
                sh.setShiftDate(d);
                sh.setCreatedAt(OffsetDateTime.now());
            }
            sh.setShiftCode("LEAVE");
            sh.setSource("OVERRIDE");
            shiftRepo.save(sh);
            changed++;
        }
        audit.record("SCHEDULE", leaveNo, actor, "LEAVE_APPROVE",
                "{\"leaveNo\":\"" + leaveNo + "\",\"staffId\":\"" + staffId
                        + "\",\"staffName\":\"" + esc(staffName)
                        + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                        + "\",\"days\":" + changed + "}");
        return changed;
    }

    /**
     * 复制上一周（B54 卡5）：为数据域内在职员工，把上一周（target-7 ~ target-1）的真实班次
     * 复制到目标周。目标日已有行一律跳过（保留手工调整/请假联动）；LEAVE 行不复制
     * （请假只由请假单批准联动产生，复制排班不得制造无单 LEAVE）。
     * 返回 [created, skipped]。整动作一条 COPY_WEEK 审计。
     */
    @Transactional
    public int[] copyWeek(LocalDate targetMonday) {
        LocalDate prevStart = targetMonday.minusDays(7);
        LocalDate prevEnd = targetMonday.minusDays(1);

        List<Staff> staff = staffRepo.findAll(
                DataScope.<Staff>storeSpec("storeCode")
                        .and((root, q, cb) -> cb.equal(root.get("status"), "在职")),
                Sort.by("staffId"));
        List<String> staffIds = staff.stream().map(Staff::getStaffId).toList();

        int created = 0;
        int skipped = 0;
        OffsetDateTime now = OffsetDateTime.now();
        String actor = DataScope.currentActor();
        if (!staffIds.isEmpty()) {
            List<StaffShift> prev = shiftRepo
                    .findByStaffIdInAndShiftDateBetweenOrderByShiftDateAscStaffIdAsc(staffIds, prevStart, prevEnd);
            for (StaffShift sh : prev) {
                if ("LEAVE".equals(sh.getShiftCode())) {
                    continue;
                }
                LocalDate targetDate = sh.getShiftDate().plusDays(7);
                if (shiftRepo.findByStaffIdAndShiftDate(sh.getStaffId(), targetDate).isPresent()) {
                    skipped++;
                    continue;
                }
                StaffShift copy = new StaffShift();
                copy.setStaffId(sh.getStaffId());
                copy.setShiftDate(targetDate);
                copy.setShiftCode(sh.getShiftCode());
                copy.setSource("TEMPLATE");
                copy.setCreatedAt(now);
                shiftRepo.save(copy);
                created++;
            }
        }

        audit.record("SCHEDULE", "week@" + targetMonday, actor, "COPY_WEEK",
                "{\"weekStart\":\"" + targetMonday + "\",\"weekEnd\":\"" + targetMonday.plusDays(6)
                        + "\",\"sourceWeekStart\":\"" + prevStart + "\",\"sourceWeekEnd\":\"" + prevEnd
                        + "\",\"staffCount\":" + staff.size()
                        + ",\"created\":" + created + ",\"skipped\":" + skipped + "}");
        return new int[]{created, skipped};
    }

    static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
