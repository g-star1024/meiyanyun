package com.meiyun.store.weekly;

import com.meiyun.security.DataScope;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WeeklyService {

    static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private static final WeekFields ISO = WeekFields.ISO;

    private static final long MAX_FEN = 100_000_000L * 100;

    private final WeeklyReportRepository repo;
    private final ConsumableAuditRecorder audit;

    public WeeklyService(WeeklyReportRepository repo, ConsumableAuditRecorder audit) {
        this.repo = repo;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "经营周报不存在");
    }

    static String isoWeekNo(LocalDate date) {
        int year = date.get(ISO.weekBasedYear());
        int week = date.get(ISO.weekOfWeekBasedYear());
        return String.format("%04d-W%02d", year, week);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String filterStoreCode) {
        List<WeeklyReport> rows;
        if ("__NONE__".equals(filterStoreCode)) {
            rows = List.of();
        } else {
            Specification<WeeklyReport> spec = DataScope.storeSpec("storeCode");
            if (filterStoreCode != null && !filterStoreCode.isBlank()) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), filterStoreCode.trim()));
            }
            rows = repo.findAll(spec, Sort.by(Sort.Direction.DESC, "startDate"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (WeeklyReport r : rows) out.add(toView(r));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        return toView(mustGet(id));
    }

    @Transactional
    public synchronized Map<String, Object> create(String storeCode, String actor) {
        WeeklyReport last = repo.findAll(
                        (root, q, cb) -> cb.equal(root.get("storeCode"), storeCode),
                        Sort.by(Sort.Direction.DESC, "startDate"))
                .stream().findFirst().orElse(null);
        LocalDate nextStart = last == null
                ? LocalDate.now(BIZ_ZONE).with(ISO.dayOfWeek(), 1)
                : last.getStartDate().plusDays(7);
        LocalDate nextEnd = nextStart.plusDays(6);
        long prevFen = last == null ? 0 : (last.getRevenueFen() == null ? 0 : last.getRevenueFen());

        WeeklyReport r = new WeeklyReport();
        r.setWeekNo(isoWeekNo(nextStart));
        r.setStoreCode(storeCode);
        r.setStartDate(nextStart);
        r.setEndDate(nextEnd);
        r.setRevenueFen(0L);
        r.setPrevRevenueFen(prevFen);
        r.setStatus("DRAFT");
        repo.save(r);
        audit.record("WEEKLY_REPORT", r.getWeekNo(), actor, "CREATE",
                "{\"weekNo\":" + jsonStr(r.getWeekNo()) + "}");
        return toView(r);
    }

    @Transactional
    public Map<String, Object> save(Long id, SaveCmd cmd, String actor) {
        if (cmd == null) throw badReq("请提供周报内容");
        WeeklyReport r = mustGet(id);
        if (!"DRAFT".equals(r.getStatus())) throw badReq("周报已提交，不可修改");
        r.setRevenueFen(checkFen(cmd.revenue(), "营业收入"));
        r.setFootfall(checkInt(cmd.footfall(), "客流"));
        r.setOrders(checkInt(cmd.orders(), "成交单数"));
        r.setNewCustomers(checkInt(cmd.newCustomers(), "新客人数"));
        r.setRepurchaseRate(checkRate(cmd.repurchaseRate()));
        r.setHighlights(trim(cmd.highlights()));
        r.setIssues(trim(cmd.issues()));
        r.setNextWeekPlan(trim(cmd.nextWeekPlan()));
        repo.save(r);
        audit.record("WEEKLY_REPORT", r.getWeekNo(), actor, "SAVE",
                "{\"weekNo\":" + jsonStr(r.getWeekNo()) + ",\"revenueYuan\":" + (r.getRevenueFen() / 100) + "}");
        return toView(r);
    }

    @Transactional
    public Map<String, Object> submit(Long id, String actor) {
        WeeklyReport r = mustGet(id);
        if (!"DRAFT".equals(r.getStatus())) throw badReq("周报已提交，请勿重复提交");
        r.setStatus("SUBMITTED");
        r.setSubmittedBy(actor);
        r.setSubmittedAt(OffsetDateTime.now(BIZ_ZONE));
        repo.save(r);
        audit.record("WEEKLY_REPORT", r.getWeekNo(), actor, "SUBMIT",
                "{\"weekNo\":" + jsonStr(r.getWeekNo()) + ",\"revenueYuan\":" + (r.getRevenueFen() / 100) + "}");
        return toView(r);
    }

    public record SaveCmd(Long revenue, Integer footfall, Integer orders, Integer newCustomers,
                          Integer repurchaseRate, String highlights, String issues, String nextWeekPlan) {}

    public record SeedReport(String weekNo, LocalDate startDate, LocalDate endDate, long revenueYuan,
                             long prevRevenueYuan, int footfall, int orders, int newCustomers,
                             int repurchaseRate, String highlights, String issues, String nextWeekPlan,
                             String status, String submittedBy, OffsetDateTime submittedAt) {}

    @Transactional
    public void seed(String storeCode, SeedReport c) {
        WeeklyReport r = new WeeklyReport();
        r.setWeekNo(c.weekNo());
        r.setStoreCode(storeCode);
        r.setStartDate(c.startDate());
        r.setEndDate(c.endDate());
        r.setRevenueFen(c.revenueYuan() * 100);
        r.setPrevRevenueFen(c.prevRevenueYuan() * 100);
        r.setFootfall(c.footfall());
        r.setOrders(c.orders());
        r.setNewCustomers(c.newCustomers());
        r.setRepurchaseRate(c.repurchaseRate());
        r.setHighlights(c.highlights());
        r.setIssues(c.issues());
        r.setNextWeekPlan(c.nextWeekPlan());
        r.setStatus(c.status());
        r.setSubmittedBy(c.submittedBy());
        r.setSubmittedAt(c.submittedAt());
        repo.save(r);
    }

    private WeeklyReport mustGet(Long id) {
        return repo.findById(id).orElseThrow(WeeklyService::notFound);
    }

    private static long checkFen(Long yuan, String label) {
        if (yuan == null) throw badReq("请提供" + label);
        if (yuan < 0) throw badReq(label + "不能为负数");
        long fen = yuan * 100;
        if (fen > MAX_FEN) throw badReq(label + "超出允许范围");
        return fen;
    }

    private static int checkInt(Integer v, String label) {
        if (v == null) throw badReq("请提供" + label);
        if (v < 0) throw badReq(label + "不能为负数");
        if (v > 1_000_000) throw badReq(label + "超出允许范围");
        return v;
    }

    private static int checkRate(Integer v) {
        if (v == null) throw badReq("请提供复购率");
        if (v < 0 || v > 100) throw badReq("复购率需在 0~100 之间");
        return v;
    }

    private static String trim(String s) {
        if (s == null) return "";
        if (s.length() > 2000) throw badReq("文本内容超出允许长度");
        return s;
    }

    private Map<String, Object> toView(WeeklyReport r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("weekNo", r.getWeekNo());
        row.put("storeCode", r.getStoreCode());
        row.put("startDate", r.getStartDate().toString());
        row.put("endDate", r.getEndDate().toString());
        row.put("revenue", (r.getRevenueFen() == null ? 0 : r.getRevenueFen()) / 100);
        row.put("prevRevenue", (r.getPrevRevenueFen() == null ? 0 : r.getPrevRevenueFen()) / 100);
        row.put("footfall", r.getFootfall());
        row.put("orders", r.getOrders());
        row.put("newCustomers", r.getNewCustomers());
        row.put("repurchaseRate", r.getRepurchaseRate());
        row.put("highlights", r.getHighlights());
        row.put("issues", r.getIssues());
        row.put("nextWeekPlan", r.getNextWeekPlan());
        row.put("status", r.getStatus());
        row.put("submittedBy", r.getSubmittedBy());
        row.put("submittedAt", r.getSubmittedAt() == null ? null : r.getSubmittedAt().toString());
        return row;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
