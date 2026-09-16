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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工请假（B54 卡5）：最小真实请假域——登记、列表、批准/驳回；批准在同一事务内把区间
 * 每日班次权威覆盖为 LEAVE + OVERRIDE（见 {@link ShiftUpsertService#applyLeaveOverride}），
 * 驳回不动排班。LEAVE 只能由真实请假单批准产生（周例铺底与复制上周均不造 LEAVE）。
 *
 * <p>类型只收 年假/事假/病假（换班为历史展示占位，不接收新登记）。状态机单向：
 * PENDING → APPROVED / REJECTED 终态；对已处于同一终态的单据重复操作幂等返回。
 *
 * <p>权限：登记持 schedule:edit，列表持 schedule:view，审批持 schedule:approve
 * （仅区域经理/店长/超管）。leave_request 不带门店字段，数据域按申请人员工所属门店断言，
 * 员工不存在/离职/越权统一 404「数据不存在或无权查看」，不泄露存在性。
 *
 * <p>审计：bizType=SCHEDULE，动作 APPLY（登记）/ APPROVE（批准）/ REJECT（驳回），
 * 批准联动的逐日覆盖另记一条 LEAVE_APPROVE（在 ShiftUpsertService 内）。
 */
@RestController
@RequestMapping("/api/org")
public class LeaveController {

    private static final List<String> TYPES = List.of("年假", "事假", "病假");
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "APPROVED");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final LeaveRequestRepository leaveRepo;
    private final StaffRepository staffRepo;
    private final ShiftUpsertService shiftUpsert;
    private final AuditRecorder audit;

    public LeaveController(LeaveRequestRepository leaveRepo, StaffRepository staffRepo,
                           ShiftUpsertService shiftUpsert, AuditRecorder audit) {
        this.leaveRepo = leaveRepo;
        this.staffRepo = staffRepo;
        this.shiftUpsert = shiftUpsert;
        this.audit = audit;
    }

    /**
     * 请假登记：POST /api/org/leaves，body={staffId,type,startDate,endDate,reason}。
     * 校验员工在职且在数据域、类型白名单、日期合法且起≤止、与同员工在途/已批准单区间不重叠；
     * 单号 LV+yyyyMMdd+三位序号。返回新建单据（PENDING）。
     */
    @PostMapping("/leaves")
    @RequirePerm("schedule:edit")
    @Transactional
    public Map<String, Object> apply(@RequestBody LeaveApplyRequest req) {
        if (req == null || req.staffId() == null || req.staffId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "员工工号不能为空");
        }
        String staffId = req.staffId().trim();
        Staff staff = staffRepo.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!"在职".equals(staff.getStatus()) || !DataScope.canReadStore(staff.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        String type = req.type() == null ? "" : req.type().trim();
        if (!TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请假类型非法，允许值：年假/事假/病假");
        }
        LocalDate start = parseDate(req.startDate(), "开始日期");
        LocalDate end = parseDate(req.endDate(), "结束日期");
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结束日期不能早于开始日期");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请假事由不能为空");
        }
        if (reason.length() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请假事由不能超过 256 字");
        }
        List<LeaveRequest> overlap = leaveRepo
                .findByStaffIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        staffId, ACTIVE_STATUSES, end, start);
        if (!overlap.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该员工在 " + start + " ~ " + end + " 区间已有在途或已批准的请假单（"
                            + overlap.get(0).getLeaveNo() + "）");
        }

        String actor = DataScope.currentActor();
        LeaveRequest lv = new LeaveRequest();
        lv.setLeaveNo(nextLeaveNo());
        lv.setStaffId(staffId);
        lv.setType(type);
        lv.setStartDate(start);
        lv.setEndDate(end);
        lv.setReason(reason);
        lv.setStatus("PENDING");
        lv.setAppliedBy(actor);
        lv.setAppliedAt(OffsetDateTime.now());
        leaveRepo.save(lv);

        audit.record("SCHEDULE", lv.getLeaveNo(), actor, "APPLY",
                "{\"leaveNo\":\"" + lv.getLeaveNo() + "\",\"staffId\":\"" + staffId
                        + "\",\"staffName\":\"" + esc(staff.getStaffName())
                        + "\",\"type\":\"" + type + "\",\"startDate\":\"" + start
                        + "\",\"endDate\":\"" + end + "\",\"reason\":\"" + esc(reason) + "\"}");
        return leaveNode(lv, staffNameOf(staff), reviewerNameOf(lv.getReviewerId()));
    }

    /**
     * 请假列表：GET /api/org/leaves。返回数据域内在职员工的全部请假单（按登记时间倒序），
     * 富化申请人姓名与审批人姓名；离职/越权员工单据不露出。
     */
    @GetMapping("/leaves")
    @RequirePerm("schedule:view")
    public List<Map<String, Object>> list() {
        List<Staff> staff = staffRepo.findAll(activeStaffInScope(), Sort.by("staffId"));
        Map<String, String> nameByStaffId = staff.stream()
                .collect(Collectors.toMap(Staff::getStaffId, Staff::getStaffName, (a, b) -> a,
                        LinkedHashMap::new));
        Set<String> inScope = nameByStaffId.keySet();
        List<Map<String, Object>> out = new ArrayList<>();
        for (LeaveRequest lv : leaveRepo.findAllByOrderByAppliedAtDescIdDesc()) {
            if (!inScope.contains(lv.getStaffId())) {
                continue;
            }
            out.add(leaveNode(lv, nameByStaffId.get(lv.getStaffId()),
                    reviewerNameOf(lv.getReviewerId())));
        }
        return out;
    }

    /**
     * 批准：POST /api/org/leaves/{id}/approve。仅 PENDING 可办；同一单据重复批准幂等返回，
     * 已驳回单据冲突 409。批准后同事务逐日覆盖班次为 LEAVE + OVERRIDE。
     */
    @PostMapping("/leaves/{id}/approve")
    @RequirePerm("schedule:approve")
    @Transactional
    public Map<String, Object> approve(@PathVariable Long id) {
        LeaveRequest lv = getInScope(id);
        if ("APPROVED".equals(lv.getStatus())) {
            return leaveNode(lv, staffNameById(lv.getStaffId()), reviewerNameOf(lv.getReviewerId()));
        }
        if ("REJECTED".equals(lv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已驳回的请假单不能批准：" + lv.getLeaveNo());
        }
        String actor = DataScope.currentActor();
        lv.setStatus("APPROVED");
        lv.setReviewerId(actor);
        lv.setReviewedAt(OffsetDateTime.now());
        leaveRepo.save(lv);

        audit.record("SCHEDULE", lv.getLeaveNo(), actor, "APPROVE",
                "{\"leaveNo\":\"" + lv.getLeaveNo() + "\",\"staffId\":\"" + lv.getStaffId()
                        + "\",\"type\":\"" + lv.getType() + "\",\"startDate\":\"" + lv.getStartDate()
                        + "\",\"endDate\":\"" + lv.getEndDate() + "\"}");
        int days = shiftUpsert.applyLeaveOverride(lv.getStaffId(), staffNameById(lv.getStaffId()),
                lv.getStartDate(), lv.getEndDate(), lv.getLeaveNo(), actor);
        Map<String, Object> node = leaveNode(lv, staffNameById(lv.getStaffId()),
                reviewerNameOf(lv.getReviewerId()));
        node.put("affectedDays", days);
        return node;
    }

    /**
     * 驳回：POST /api/org/leaves/{id}/reject，body={rejectReason?}。仅 PENDING 可办；
     * 重复驳回幂等返回；已批准单据冲突 409。驳回不动排班。
     */
    @PostMapping("/leaves/{id}/reject")
    @RequirePerm("schedule:approve")
    @Transactional
    public Map<String, Object> reject(@PathVariable Long id, @RequestBody(required = false) LeaveRejectRequest req) {
        LeaveRequest lv = getInScope(id);
        if ("REJECTED".equals(lv.getStatus())) {
            return leaveNode(lv, staffNameById(lv.getStaffId()), reviewerNameOf(lv.getReviewerId()));
        }
        if ("APPROVED".equals(lv.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已批准的请假单不能驳回：" + lv.getLeaveNo());
        }
        String reason = req == null || req.rejectReason() == null ? "" : req.rejectReason().trim();
        if (reason.length() > 256) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回原因不能超过 256 字");
        }
        String actor = DataScope.currentActor();
        lv.setStatus("REJECTED");
        lv.setReviewerId(actor);
        lv.setReviewedAt(OffsetDateTime.now());
        lv.setRejectReason(reason.isBlank() ? null : reason);
        leaveRepo.save(lv);

        audit.record("SCHEDULE", lv.getLeaveNo(), actor, "REJECT",
                "{\"leaveNo\":\"" + lv.getLeaveNo() + "\",\"staffId\":\"" + lv.getStaffId()
                        + "\",\"type\":\"" + lv.getType() + "\",\"rejectReason\":\""
                        + esc(reason) + "\"}");
        return leaveNode(lv, staffNameById(lv.getStaffId()), reviewerNameOf(lv.getReviewerId()));
    }

    // ==================== 内部方法 ====================

    /** 取单并断言申请人仍在职且在审批人数据域；不存在/离职/越权统一 404。 */
    private LeaveRequest getInScope(Long id) {
        LeaveRequest lv = leaveRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        Staff staff = staffRepo.findById(lv.getStaffId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!"在职".equals(staff.getStatus()) || !DataScope.canReadStore(staff.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return lv;
    }

    /** 单号 LV+yyyyMMdd+三位序号：当天已有单则序号 +1，否则 001（演示串行登记，取末单解析）。 */
    private String nextLeaveNo() {
        String prefix = "LV" + LocalDate.now().format(DAY);
        int seq = leaveRepo.findFirstByOrderByIdDesc()
                .map(LeaveRequest::getLeaveNo)
                .filter(n -> n != null && n.startsWith(prefix) && n.length() == prefix.length() + 3)
                .map(n -> n.substring(prefix.length()))
                .map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .map(n -> n + 1)
                .orElse(1);
        return prefix + String.format("%03d", Math.min(seq, 999));
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能为空");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "格式应为 yyyy-MM-dd");
        }
    }

    private static Specification<Staff> activeStaffInScope() {
        return DataScope.<Staff>storeSpec("storeCode")
                .and((root, q, cb) -> cb.equal(root.get("status"), "在职"));
    }

    private String staffNameById(String staffId) {
        return staffRepo.findById(staffId).map(Staff::getStaffName).orElse("");
    }

    private static String staffNameOf(Staff staff) {
        return staff.getStaffName();
    }

    /** 审批人存的是登录名，回显姓名按 loginName 反查；查不到（如 system）原样回退。 */
    private String reviewerNameOf(String reviewerId) {
        if (reviewerId == null || reviewerId.isBlank()) {
            return null;
        }
        return staffRepo.findByLoginName(reviewerId)
                .map(Staff::getStaffName)
                .orElse(reviewerId);
    }

    private static Map<String, Object> leaveNode(LeaveRequest lv, String staffName, String reviewerName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", lv.getId());
        m.put("leaveNo", lv.getLeaveNo());
        m.put("staffId", lv.getStaffId());
        m.put("staffName", staffName);
        m.put("type", lv.getType());
        m.put("startDate", lv.getStartDate().toString());
        m.put("endDate", lv.getEndDate().toString());
        m.put("reason", lv.getReason());
        m.put("status", lv.getStatus());
        m.put("appliedBy", lv.getAppliedBy());
        m.put("appliedAt", lv.getAppliedAt());
        m.put("reviewerId", lv.getReviewerId());
        m.put("reviewerName", reviewerName);
        m.put("reviewedAt", lv.getReviewedAt());
        m.put("rejectReason", lv.getRejectReason());
        return m;
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 请求体记录 ====================

    public record LeaveApplyRequest(String staffId, String type, String startDate,
                                    String endDate, String reason) {
    }

    public record LeaveRejectRequest(String rejectReason) {
    }
}
