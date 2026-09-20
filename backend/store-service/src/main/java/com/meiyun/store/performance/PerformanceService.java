package com.meiyun.store.performance;

import com.meiyun.security.DataScope;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PerformanceService {

    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    static final long MAX_TARGET_FEN = 100_000_000L * 100;

    private final PerfStaffRepository staffRepo;
    private final ConsumableAuditRecorder audit;

    public PerformanceService(PerfStaffRepository staffRepo, ConsumableAuditRecorder audit) {
        this.staffRepo = staffRepo;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "员工绩效不存在");
    }

    static YearMonth resolveMonth(String period) {
        YearMonth now = YearMonth.now(BIZ_ZONE);
        if (period == null || period.isBlank() || "THIS_MONTH".equals(period.trim())) return now;
        if ("LAST_MONTH".equals(period.trim())) return now.minusMonths(1);
        throw badReq("不支持的周期：" + period);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String period, String role, String filterStoreCode) {
        YearMonth month = resolveMonth(period);
        String periodStr = month.toString();
        List<PerfStaff> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<PerfStaff> spec = DataScope.storeSpec("storeCode");
            spec = spec.and((root, q, cb) -> cb.equal(root.get("period"), periodStr));
            if (role != null && !role.isBlank() && !"ALL".equals(role.trim())) {
                String r = role.trim();
                if (!"CONSULTANT".equals(r) && !"DOCTOR".equals(r) && !"BEAUTICIAN".equals(r))
                    throw badReq("不支持的岗位：" + r);
                spec = spec.and((root, q, cb) -> cb.equal(root.get("role"), r));
            }
            if (filterStoreCode != null && !filterStoreCode.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            rows = staffRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "actualFen"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PerfStaff s : rows) out.add(toView(s));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public Map<String, Object> updateTarget(Long id, TargetCmd cmd, String actor) {
        if (cmd == null || cmd.target() == null) throw badReq("请提供目标金额");
        PerfStaff s = mustGet(id);
        long targetYuan = cmd.target();
        if (targetYuan < 0) throw badReq("业绩目标不能为负数");
        long targetFen = targetYuan * 100;
        if (targetFen > MAX_TARGET_FEN) throw badReq("业绩目标超出允许范围");
        long before = s.getTargetFen() == null ? 0 : s.getTargetFen();
        s.setTargetFen(targetFen);
        staffRepo.save(s);
        audit.record("PERFORMANCE", String.valueOf(s.getId()), actor, "UPDATE_TARGET",
                "{\"name\":" + jsonStr(s.getName()) + ",\"beforeYuan\":" + (before / 100)
                        + ",\"targetYuan\":" + targetYuan + "}");
        return detail(id);
    }

    public record TargetCmd(Long target) {}

    public record SeedStaff(String storeCode, String name, String role, String title,
                            String avatarLetter, long targetYuan, long actualYuan, int orders,
                            int commissionBp, String status, LocalDate joinedAt, List<Integer> trend) {}

    @Transactional
    public void seed(String period, SeedStaff cmd) {
        PerfStaff s = new PerfStaff();
        s.setPeriod(period);
        s.setStoreCode(cmd.storeCode());
        s.setName(cmd.name());
        s.setRole(cmd.role());
        s.setTitle(cmd.title());
        s.setAvatarLetter(cmd.avatarLetter());
        s.setTargetFen(cmd.targetYuan() * 100);
        s.setActualFen(cmd.actualYuan() * 100);
        s.setOrders(cmd.orders());
        s.setCommissionBp(cmd.commissionBp());
        s.setStatus(cmd.status());
        s.setJoinedAt(cmd.joinedAt());
        List<Integer> trend = new ArrayList<>(cmd.trend() == null ? List.of() : cmd.trend());
        while (trend.size() < 6) trend.add(0);
        s.setTrend(trend.subList(0, 6));
        staffRepo.save(s);
    }

    private PerfStaff mustGet(Long id) {
        return staffRepo.findById(id).orElseThrow(PerformanceService::notFound);
    }

    private Map<String, Object> toView(PerfStaff s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("period", s.getPeriod());
        row.put("storeCode", s.getStoreCode());
        row.put("name", s.getName());
        row.put("role", s.getRole());
        row.put("title", s.getTitle());
        row.put("avatarLetter", s.getAvatarLetter());
        row.put("target", (s.getTargetFen() == null ? 0 : s.getTargetFen()) / 100);
        row.put("actual", (s.getActualFen() == null ? 0 : s.getActualFen()) / 100);
        row.put("orders", s.getOrders());
        row.put("commissionRate", (s.getCommissionBp() == null ? 0 : s.getCommissionBp()) / 10000.0);
        row.put("status", s.getStatus());
        row.put("joinedAt", s.getJoinedAt().toString());
        List<Integer> trend = new ArrayList<>(s.getTrend());
        while (trend.size() < 6) trend.add(0);
        row.put("trend", trend.subList(0, 6));
        return row;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
