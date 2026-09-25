package com.meiyun.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * T2 事实表月度结算（P5-B97，DESIGN-T2 §3 D5）：
 * 单月全量重算 monthly_store_metrics——7 计数取自 txn internal 聚合三端点
 * （metric-customers/metric-funnel/metric-treatments，跨域一律 X-Internal-Token 不直 JOIN），
 * 3 财务快照取 revenue_monthly 同月行（未结算留 null，读取侧显「—」）。
 * 计数类源端点无行补 0（COUNT 语义确定）；四源全无数据的门店不写行（零伪造）。
 * 复合 PK（storeCode+periodMonth）幂等 upsert，任意重跑收敛同值；
 * 写后落 METRIC_MONTHLY/RUN SYSTEM 审计（有产出才落，审计失败不阻断结算）。
 */
@Service
public class MetricMonthlyService {

    private static final Logger log = LoggerFactory.getLogger(MetricMonthlyService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACTOR = "SYSTEM";

    private final MonthlyStoreMetricRepository metricRepo;
    private final RevenueMonthlyRepository revenueRepo;
    private final FinanceAggregationService agg;
    private final FinanceAuditRecorder audit;

    public MetricMonthlyService(MonthlyStoreMetricRepository metricRepo, RevenueMonthlyRepository revenueRepo,
                                FinanceAggregationService agg, FinanceAuditRecorder audit) {
        this.metricRepo = metricRepo;
        this.revenueRepo = revenueRepo;
        this.agg = agg;
        this.audit = audit;
    }

    /** 结算单月：全量重算＋幂等 upsert；返回写入行数（0＝四源全无数据，不落审计）。 */
    @Transactional
    public int settleMonth(LocalDate periodMonth) {
        String ym = periodMonth.format(DateTimeFormatter.ofPattern("yyyy-MM"));

        Map<String, Map<String, Object>> customers = byStore(agg.fetchMetricCustomers(ym));
        Map<String, Map<String, Object>> funnel = byStore(agg.fetchMetricFunnel(ym));
        Map<String, Map<String, Object>> treatments = byStore(agg.fetchMetricTreatments(ym));
        Map<String, RevenueMonthly> revenue = new LinkedHashMap<>();
        for (RevenueMonthly r : revenueRepo.findByPeriodMonthOrderByStoreCodeAsc(periodMonth)) {
            if (r.getStoreCode() != null) revenue.put(r.getStoreCode(), r);
        }

        TreeSet<String> stores = new TreeSet<>();
        stores.addAll(customers.keySet());
        stores.addAll(funnel.keySet());
        stores.addAll(treatments.keySet());
        stores.addAll(revenue.keySet());

        Instant now = Instant.now();
        int written = 0;
        for (String storeCode : stores) {
            Map<String, Object> c = customers.get(storeCode);
            Map<String, Object> f = funnel.get(storeCode);
            Map<String, Object> t = treatments.get(storeCode);
            RevenueMonthly r = revenue.get(storeCode);

            MonthlyStoreMetric m = metricRepo
                    .findById(new MonthlyStoreMetric.MonthlyStoreMetricId(storeCode, periodMonth))
                    .orElseGet(MonthlyStoreMetric::new);
            m.setStoreCode(storeCode);
            m.setPeriodMonth(periodMonth);
            m.setNewCustomers(longOrZero(c == null ? null : c.get("newCustomers")));
            m.setRepurchaseCount(longOrZero(c == null ? null : c.get("repurchaseCount")));
            m.setActiveCustomers(longOrZero(c == null ? null : c.get("activeCustomers")));
            m.setArrivalCount(longOrZero(f == null ? null : f.get("arrivalCount")));
            m.setConsultCount(longOrZero(f == null ? null : f.get("consultCount")));
            m.setDealCount(longOrZero(f == null ? null : f.get("dealCount")));
            m.setTreatmentCount(longOrZero(t == null ? null : t.get("treatmentCount")));
            m.setRevenue(r == null ? null : r.getRevenue());
            m.setCost(r == null ? null : r.getCost());
            m.setGrossProfit(r == null ? null : r.getGrossProfit());
            m.setComputedAt(now);
            metricRepo.save(m);
            written++;
        }

        if (written > 0) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("periodMonth", ym);
                payload.put("stores", stores.size());
                payload.put("rows", written);
                audit.record("METRIC_MONTHLY", "METRIC-" + ym, ACTOR, "RUN", MAPPER.writeValueAsString(payload));
            } catch (Exception e) {
                log.warn("METRIC_MONTHLY 审计落链失败（不阻断结算）: {}", e.getMessage());
            }
        }
        log.info("月度事实表结算完成 {}: 写入 {} 行（门店 {} 个）", ym, written, stores.size());
        return written;
    }

    private static Map<String, Map<String, Object>> byStore(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object storeCode = row.get("storeCode");
            if (storeCode != null) out.put(String.valueOf(storeCode), row);
        }
        return out;
    }

    private static Long longOrZero(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
