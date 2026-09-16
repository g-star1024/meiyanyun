package com.meiyun.org;

import com.meiyun.org.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 员工周排班（B54 卡1）：staff_shift 真源的浏览器周视图读、单日改班、周例铺底。
 *
 * <p>全员排班（数据域内在职员工全部入格，不限医生/角色）；房间/设备不排班，按营业窗处理。
 * 班次五态：MORNING 上午 / MID 下午 / FULL 全天 / OFF 休息 / LEAVE 请假，OFF/LEAVE 不可派单。
 * source=TEMPLATE 周例铺底、OVERRIDE 单日手工（卡5 起含请假联动）。
 *
 * <p>权限：读持 schedule:view（八角色全有），写持 schedule:edit（仅区域经理/店长/超管）。
 * 数据域：员工集走门店数据域（STORE 本店 / REGION 本区门店 / GROUP 全量），大区编制账号
 * （store_code 为空）不参与门店排班；单日写越权员工统一 404「数据不存在或无权查看」。
 *
 * <p>审计：bizType=SCHEDULE，动作 SET（单日改班）/ GENERATE_WEEK（周例铺底），payload 合法 JSON；
 * 同码重复提交幂等返回、不重复记审计；周例不覆盖已存在行（保留手工调整）。
 */
@RestController
@RequestMapping("/api/org")
public class ScheduleController {

    /** 班次码 → 中文文案 / 是否可派单（字典唯一事实源，前端不得自造映射）。 */
    private static final Map<String, ShiftCodeDef> SHIFT_CODES = new LinkedHashMap<>();
    static {
        SHIFT_CODES.put("MORNING", new ShiftCodeDef("MORNING", "上午", true));
        SHIFT_CODES.put("MID", new ShiftCodeDef("MID", "下午", true));
        SHIFT_CODES.put("FULL", new ShiftCodeDef("FULL", "全天", true));
        SHIFT_CODES.put("OFF", new ShiftCodeDef("OFF", "休息", false));
        SHIFT_CODES.put("LEAVE", new ShiftCodeDef("LEAVE", "请假", false));
    }

    private final StaffRepository staffRepo;
    private final StaffShiftRepository shiftRepo;
    private final AuditRecorder audit;

    public ScheduleController(StaffRepository staffRepo, StaffShiftRepository shiftRepo, AuditRecorder audit) {
        this.staffRepo = staffRepo;
        this.shiftRepo = shiftRepo;
        this.audit = audit;
    }

    /**
     * 周排班视图：GET /api/org/schedule?weekStart=yyyy-MM-dd（可省，默认本周）。
     * 返回该周周一至周日、数据域内在职员工清单与其班次行；未排日期前端按空态处理，不替用户造班。
     */
    @GetMapping("/schedule")
    @RequirePerm("schedule:view")
    public Map<String, Object> weekView(@RequestParam(value = "weekStart", required = false) String weekStart) {
        LocalDate monday = resolveMonday(weekStart);
        LocalDate sunday = monday.plusDays(6);

        List<Staff> staff = staffRepo.findAll(activeStaffInScope(), Sort.by("staffId"));

        List<String> staffIds = staff.stream().map(Staff::getStaffId).toList();
        List<StaffShift> shifts = staffIds.isEmpty()
                ? List.of()
                : shiftRepo.findByStaffIdInAndShiftDateBetweenOrderByShiftDateAscStaffIdAsc(
                        staffIds, monday, sunday);

        List<Map<String, Object>> staffOut = new ArrayList<>();
        for (Staff s : staff) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("staffId", s.getStaffId());
            m.put("staffName", s.getStaffName());
            m.put("storeCode", s.getStoreCode());
            m.put("roleCode", s.getRoleCode());
            staffOut.add(m);
        }
        List<Map<String, Object>> shiftOut = new ArrayList<>();
        for (StaffShift sh : shifts) {
            shiftOut.add(shiftNode(sh));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weekStart", monday.toString());
        out.put("weekEnd", sunday.toString());
        out.put("staff", staffOut);
        out.put("shifts", shiftOut);
        return out;
    }

    /** 班次五态字典：GET /api/org/shift-codes（内部码 + 中文文案 + 是否可派单）。 */
    @GetMapping("/shift-codes")
    @RequirePerm("schedule:view")
    public List<ShiftCodeDef> shiftCodes() {
        return new ArrayList<>(SHIFT_CODES.values());
    }

    /**
     * 单日改班（周网格点选循环的落库端点）：PUT /api/org/schedule/shift，
     * body={staffId, shiftDate(yyyy-MM-dd), shiftCode}。手工改动一律落 source=OVERRIDE；
     * 同日同码重复提交幂等返回不重复审计；员工不存在/离职/越权统一 404 不泄露存在性。
     */
    @PutMapping("/schedule/shift")
    @RequirePerm("schedule:edit")
    @Transactional
    public StaffShift setShift(@RequestBody ShiftSetRequest req) {
        if (req == null || req.staffId() == null || req.staffId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "员工工号不能为空");
        }
        if (req.shiftDate() == null || req.shiftDate().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "班次日期不能为空");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(req.shiftDate().trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "班次日期格式应为 yyyy-MM-dd");
        }
        String code = req.shiftCode() == null ? "" : req.shiftCode().trim().toUpperCase();
        if (!SHIFT_CODES.containsKey(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "班次码非法，允许值：MORNING/MID/FULL/OFF/LEAVE");
        }
        Staff staff = staffRepo.findById(req.staffId().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!"在职".equals(staff.getStatus()) || !DataScope.canReadStore(staff.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }

        String staffId = staff.getStaffId();
        Optional<StaffShift> existing = shiftRepo.findByStaffIdAndShiftDate(staffId, date);
        if (existing.isPresent()) {
            StaffShift sh = existing.get();
            if (code.equals(sh.getShiftCode())) {
                return sh;
            }
            sh.setShiftCode(code);
            sh.setSource("OVERRIDE");
            shiftRepo.save(sh);
            audit.record("SCHEDULE", staffId + "@" + date, DataScope.currentActor(), "SET",
                    "{\"staffId\":\"" + staffId + "\",\"staffName\":\"" + esc(staff.getStaffName())
                            + "\",\"shiftDate\":\"" + date + "\",\"shiftCode\":\"" + code
                            + "\",\"source\":\"OVERRIDE\"}");
            return sh;
        }

        StaffShift sh = new StaffShift();
        sh.setStaffId(staffId);
        sh.setShiftDate(date);
        sh.setShiftCode(code);
        sh.setSource("OVERRIDE");
        sh.setCreatedAt(OffsetDateTime.now());
        shiftRepo.save(sh);
        audit.record("SCHEDULE", staffId + "@" + date, DataScope.currentActor(), "SET",
                "{\"staffId\":\"" + staffId + "\",\"staffName\":\"" + esc(staff.getStaffName())
                        + "\",\"shiftDate\":\"" + date + "\",\"shiftCode\":\"" + code
                        + "\",\"source\":\"OVERRIDE\"}");
        return sh;
    }

    /**
     * 周例铺底：POST /api/org/schedule/generate-week，body={weekStart?(yyyy-MM-dd)}。
     * 为数据域内每位在职员工铺一周：周一至周六 FULL 全天、周日 OFF 休息；已存在行（含手工 OVERRIDE
     * 与既铺 TEMPLATE）一律跳过不覆盖。返回 created/skipped 计数，整动作一条 GENERATE_WEEK 审计。
     */
    @PostMapping("/schedule/generate-week")
    @RequirePerm("schedule:edit")
    @Transactional
    public Map<String, Object> generateWeek(@RequestBody(required = false) GenerateWeekRequest req) {
        LocalDate monday = resolveMonday(req == null ? null : req.weekStart());

        List<Staff> staff = staffRepo.findAll(activeStaffInScope(), Sort.by("staffId"));

        int created = 0;
        int skipped = 0;
        OffsetDateTime now = OffsetDateTime.now();
        for (Staff s : staff) {
            for (int i = 0; i < 7; i++) {
                LocalDate date = monday.plusDays(i);
                String code = date.getDayOfWeek() == DayOfWeek.SUNDAY ? "OFF" : "FULL";
                if (shiftRepo.findByStaffIdAndShiftDate(s.getStaffId(), date).isPresent()) {
                    skipped++;
                    continue;
                }
                StaffShift sh = new StaffShift();
                sh.setStaffId(s.getStaffId());
                sh.setShiftDate(date);
                sh.setShiftCode(code);
                sh.setSource("TEMPLATE");
                sh.setCreatedAt(now);
                shiftRepo.save(sh);
                created++;
            }
        }

        audit.record("SCHEDULE", "week@" + monday, DataScope.currentActor(), "GENERATE_WEEK",
                "{\"weekStart\":\"" + monday + "\",\"weekEnd\":\"" + monday.plusDays(6)
                        + "\",\"staffCount\":" + staff.size()
                        + ",\"created\":" + created + ",\"skipped\":" + skipped + "}");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weekStart", monday.toString());
        out.put("weekEnd", monday.plusDays(6).toString());
        out.put("staffCount", staff.size());
        out.put("created", created);
        out.put("skipped", skipped);
        return out;
    }

    /**
     * 服务间排班解析（B54 卡2，txn 调度派单真源）：GET /api/org/internal/schedule/resolve?staffId=&date=，
     * X-Internal-Token 系统身份（网关外 404 隐身）。返回该员工当日班次与可派钟点窗：
     * FULL 全天 09:00-20:00、MORNING 上午 09:00-14:00、MID 下午 14:00-20:00；
     * OFF 休息 / LEAVE 请假 assignable=false（不可派）；无排班行 present=false（调用方按未排班软降级）。
     * 不做数据域断言（系统身份 GROUP），也不校验在职状态（离职员工不应再有派单入口，由调用方既有在职校验兜底）。
     */
    @GetMapping("/internal/schedule/resolve")
    @RequirePerm("internal:name-map")
    public ShiftWindow resolveShift(@RequestParam String staffId, @RequestParam String date) {
        if (staffId == null || staffId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "staffId 不能为空");
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date == null ? "" : date.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date 格式应为 yyyy-MM-dd");
        }
        StaffShift sh = shiftRepo.findByStaffIdAndShiftDate(staffId.trim(), day).orElse(null);
        if (sh == null) {
            return new ShiftWindow(staffId.trim(), day.toString(), null, null,
                    false, false, null, null);
        }
        ShiftCodeDef def = SHIFT_CODES.get(sh.getShiftCode());
        if (def == null || !def.assignable()) {
            return new ShiftWindow(sh.getStaffId(), day.toString(), sh.getShiftCode(), sh.getSource(),
                    true, false, null, null);
        }
        String start = "MID".equals(sh.getShiftCode()) ? "14:00" : "09:00";
        String end = "MORNING".equals(sh.getShiftCode()) ? "14:00" : "20:00";
        return new ShiftWindow(sh.getStaffId(), day.toString(), sh.getShiftCode(), sh.getSource(),
                true, true, start, end);
    }

    // ==================== 内部方法 ====================

    /** 数据域内在职员工（门店数据域 + status=在职；大区编制无门店账号不参与门店排班）。 */
    private static Specification<Staff> activeStaffInScope() {
        return DataScope.<Staff>storeSpec("storeCode")
                .and((root, q, cb) -> cb.equal(root.get("status"), "在职"));
    }

    /** 解析周参数：空值取本周；非法格式 400；任意日期归一化到其所在周周一。 */
    private static LocalDate resolveMonday(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now().with(DayOfWeek.MONDAY);
        }
        try {
            return LocalDate.parse(raw.trim()).with(DayOfWeek.MONDAY);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weekStart 格式应为 yyyy-MM-dd");
        }
    }

    private static Map<String, Object> shiftNode(StaffShift sh) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sh.getId());
        m.put("staffId", sh.getStaffId());
        m.put("shiftDate", sh.getShiftDate().toString());
        m.put("shiftCode", sh.getShiftCode());
        m.put("source", sh.getSource());
        return m;
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 字典与请求体记录 ====================

    public record ShiftCodeDef(String code, String label, boolean assignable) {
    }

    /** 服务间单日班次解析结果：present=是否有排班行；assignable+windowStart/windowEnd=可派钟点窗（OFF/LEAVE/无行均为 null）。 */
    public record ShiftWindow(String staffId, String date, String shiftCode, String source,
                              boolean present, boolean assignable,
                              String windowStart, String windowEnd) {
    }

    public record ShiftSetRequest(String staffId, String shiftDate, String shiftCode) {
    }

    public record GenerateWeekRequest(String weekStart) {
    }
}
