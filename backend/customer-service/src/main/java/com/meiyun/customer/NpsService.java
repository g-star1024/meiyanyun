package com.meiyun.customer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NPS 回访服务（M3-B1 / DESIGN-M3 §3 M3-12）。
 *
 * <p>列表/汇总/趋势直读 nps_record：汇总分类计数＋NPS 分（推荐者%−贬损者%）与前端
 * computed 同口径；回收率 = 评价数 / 触达数（触达数取 m3_settings.npsReachCount，B1 配置化供数，
 * 真实问卷触达链路 B2+ 接管）；趋势按 ISO 周聚合近 6 周，无数据周补零（M3-12 六柱形态保真）。
 *
 * <p>提交幂等（DESIGN L124）：(customer_id, period) 先查后插，并发撞 V54 部分唯一索引
 * 捕 DataIntegrityViolationException 重查返 dedup=true（照 V52 touch_event 采集幂等先例）。
 */
@Service
public class NpsService {

    private final NpsRecordRepository repo;
    private final CustomerRepository customerRepo;
    private final M3SettingsService settingsService;

    public NpsService(NpsRecordRepository repo, CustomerRepository customerRepo,
                      M3SettingsService settingsService) {
        this.repo = repo;
        this.customerRepo = customerRepo;
        this.settingsService = settingsService;
    }

    /** 列表数据源：按提交时刻倒序（M3-12 列表/详情；数据量级小，全量返前端内存过滤）。 */
    @Transactional(readOnly = true)
    public List<NpsRecord> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    /** 汇总：分类计数/NPS 分/占比/回收率（与前端 nps store computed 同口径）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        List<NpsRecord> all = repo.findAll();
        long total = all.size();
        long promoters = all.stream().filter(r -> "PROMOTER".equals(r.getCategory())).count();
        long passives = all.stream().filter(r -> "PASSIVE".equals(r.getCategory())).count();
        long detractors = all.stream().filter(r -> "DETRACTOR".equals(r.getCategory())).count();
        long pending = all.stream().filter(r -> "PENDING".equals(r.getFollowStatus())).count();
        int reach = settingsService.getReachCount();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("promoters", promoters);
        m.put("passives", passives);
        m.put("detractors", detractors);
        m.put("pending", pending);
        m.put("npsScore", total > 0 ? Math.round(promoters * 100.0 / total - detractors * 100.0 / total) : 0);
        m.put("promoterPct", pct(promoters, total));
        m.put("passivePct", pct(passives, total));
        m.put("detractorPct", pct(detractors, total));
        m.put("reachCount", reach);
        m.put("responseRate", reach > 0 ? Math.round(total * 100.0 / reach) : 0);
        return m;
    }

    /** 趋势：近 6 周按 ISO 周聚合（本周起往前 5 周），无数据周补零（六柱形态保真）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trend() {
        LocalDate today = LocalDate.now();
        String startPeriod = isoWeek(today.minusWeeks(5));
        Map<String, Object[]> byPeriod = new LinkedHashMap<>();
        for (Object[] row : repo.trendSince(startPeriod)) {
            byPeriod.put(String.valueOf(row[0]), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            String period = isoWeek(today.minusWeeks(i));
            Object[] row = byPeriod.get(period);
            long total = row == null ? 0 : ((Number) row[1]).longValue();
            long promoters = row == null ? 0 : ((Number) row[2]).longValue();
            long passives = row == null ? 0 : ((Number) row[3]).longValue();
            long detractors = row == null ? 0 : ((Number) row[4]).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", period);
            m.put("nps", total > 0 ? Math.round(promoters * 100.0 / total - detractors * 100.0 / total) : 0);
            m.put("promoters", promoters);
            m.put("passives", passives);
            m.put("detractors", detractors);
            m.put("total", total);
            out.add(m);
        }
        return out;
    }

    /** 提交结果：record=落库（或已存在）记录，dedup=true 表示命中 (customer_id,period) 幂等未重复插入。 */
    public record SubmitResult(NpsRecord record, boolean dedup) {}

    /**
     * 提交 NPS 回执：校验四件套＋category 服务层推导＋姓名富化 customer_id＋ISO 周 period。
     * 无类级事务（saveAndFlush 撞唯一约束后需新事务重查，避免 rollback-only 污染）。
     */
    public synchronized SubmitResult submit(String customer, Integer score, String service,
                                            List<String> tags, String comment) {
        String name = trim(customer);
        if (name.isEmpty()) throw new CustomerService.BadReq("客户姓名不能为空");
        if (name.length() > 64) throw new CustomerService.BadReq("客户姓名最长 64 字");
        if (score == null || score < 0 || score > 10) throw new CustomerService.BadReq("NPS 打分须为 0-10 整数");
        String svc = trim(service);
        if (svc.length() > 128) throw new CustomerService.BadReq("回访项目最长 128 字");
        String cmt = trim(comment);
        if (cmt.length() > 500) throw new CustomerService.BadReq("评语最长 500 字");
        List<String> tagList = normalizeTags(tags);

        String category = score >= 9 ? "PROMOTER" : score >= 7 ? "PASSIVE" : "DETRACTOR";
        String period = isoWeek(LocalDate.now());
        String customerId = customerRepo.findFirstByNameAndMergedIntoIsNullOrderByCreatedAtDesc(name)
                .map(Customer::getCustomerId).orElse(null);

        if (customerId != null) {
            Optional<NpsRecord> dup = repo.findByCustomerIdAndPeriod(customerId, period);
            if (dup.isPresent()) return new SubmitResult(dup.get(), true);
        }

        NpsRecord r = new NpsRecord();
        r.setRecordNo(nextRecordNo());
        r.setCustomerId(customerId);
        r.setCustomerName(name);
        r.setScore(score);
        r.setCategory(category);
        r.setService(svc);
        r.setTags(tagList);
        r.setComment(cmt);
        r.setPeriod(period);
        r.setFollowStatus("PENDING");
        try {
            return new SubmitResult(repo.saveAndFlush(r), false);
        } catch (DataIntegrityViolationException e) {
            // 并发撞 (customer_id, period) 唯一索引：重查返 dedup（采集幂等，照 V52 先例）
            if (customerId != null) {
                Optional<NpsRecord> existing = repo.findByCustomerIdAndPeriod(customerId, period);
                if (existing.isPresent()) return new SubmitResult(existing.get(), true);
            }
            throw e;
        }
    }

    /** 标记已跟进（PENDING→FOLLOWED＋备注；重复标记幂等覆盖备注）。 */
    @Transactional
    public NpsRecord markFollowed(String recordNo, String note) {
        NpsRecord r = repo.findByRecordNo(recordNo)
                .orElseThrow(() -> new CustomerService.NotFound("NPS 记录不存在: " + recordNo));
        String n = trim(note);
        if (n.isEmpty()) throw new CustomerService.BadReq("跟进备注不能为空");
        if (n.length() > 500) throw new CustomerService.BadReq("跟进备注最长 500 字");
        r.setFollowStatus("FOLLOWED");
        r.setFollowNote(n);
        return repo.save(r);
    }

    // ---- 内部 ----

    /** ISO 周 'YYYY-Wnn'（week-based-year，字典序与时间序一致）。 */
    static String isoWeek(LocalDate d) {
        return String.format("%d-W%02d",
                d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static int pct(long part, long total) {
        return total > 0 ? (int) Math.round(part * 100.0 / total) : 0;
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : tags) {
            String v = trim(t);
            if (v.isEmpty()) continue;
            if (v.length() > 32) throw new CustomerService.BadReq("单个标签最长 32 字");
            if (out.size() >= 10) throw new CustomerService.BadReq("标签最多 10 个");
            out.add(v);
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 生成下一个记录单号：NR+4 位序号，基于库内最大 NR 号递增（synchronized 防并发重号）。 */
    private String nextRecordNo() {
        String max = repo.maxRecordNo();
        int seq = 0;
        if (max != null && max.startsWith("NR")) {
            try {
                seq = Integer.parseInt(max.substring(2));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return String.format("NR%04d", seq + 1);
    }
}
