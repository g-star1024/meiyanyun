package com.meiyun.txn;

import com.meiyun.security.DataScope;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M1 数据大屏快照聚合（B49 卡7）。口径与 InternalDailyController daily-metrics 先例一致：
 * Asia/Shanghai 自然日、营收 = status「已收款」订单按 createdAt 落日（非支付完成时点）、
 * 金额单位「分」（fen 后缀键名/标记，卡4 一致）。
 *
 * <p>跨店聚合走铁律 -1-D 跨店例外域：DataScope.storeSpec("storeCode") 收敛当前登录人数据域
 * （GROUP/超管全量、REGION 域内门店、STORE 仅本店），Java 内存聚合（卡4 group-overview 先例）；
 * 门店名不在后端 join，由前端经 /api/stores 映射。
 */
@Service
public class ScreenService {

    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");

    /** 服务大类 serviceCategory 五枚举 → 中文（空串/未命中 → 其他）。 */
    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "INJECTION", "注射美容",
            "LASER", "光电美肤",
            "SKINCARE", "皮肤护理",
            "BODY", "形体管理",
            "EXAM", "检测咨询");
    private static final String CATEGORY_OTHER = "其他";

    private final TxnOrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final ArrivalRepository arrivalRepo;
    private final AppointmentRepository apptRepo;
    private final StoreProjectClient projectClient;

    public ScreenService(TxnOrderRepository orderRepo,
                         OrderItemRepository itemRepo,
                         ArrivalRepository arrivalRepo,
                         AppointmentRepository apptRepo,
                         StoreProjectClient projectClient) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.arrivalRepo = arrivalRepo;
        this.apptRepo = apptRepo;
        this.projectClient = projectClient;
    }

    /** 大屏快照：KPI 六项（较昨日同口径）+ hourly 0-23 分桶 + 品类占比 + 门店 top5 + 口径注。 */
    public ScreenOverviewView overview() {
        LocalDate today = LocalDate.now(BJ);
        LocalDate yesterday = today.minusDays(1);
        OffsetDateTime tStart = today.atStartOfDay(BJ).toOffsetDateTime();
        OffsetDateTime tEnd = today.plusDays(1).atStartOfDay(BJ).toOffsetDateTime();
        OffsetDateTime yStart = yesterday.atStartOfDay(BJ).toOffsetDateTime();

        List<TxnOrder> paidToday = orderRepo.findAll(paidSpec(tStart, tEnd));
        List<TxnOrder> paidYesterday = orderRepo.findAll(paidSpec(yStart, tStart));

        long revToday = sumAmount(paidToday);
        long revYesterday = sumAmount(paidYesterday);
        long cntToday = paidToday.size();
        long cntYesterday = paidYesterday.size();
        long avgToday = cntToday == 0 ? 0L : revToday / cntToday;
        Long avgYesterday = cntYesterday == 0 ? null : revYesterday / cntYesterday;

        long[] bucket = new long[24];
        for (TxnOrder o : paidToday) {
            if (o.getCreatedAt() != null && o.getAmount() != null) {
                bucket[o.getCreatedAt().atZoneSameInstant(BJ).getHour()] += o.getAmount();
            }
        }
        List<HourlyPoint> hourly = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            hourly.add(new HourlyPoint(String.format("%02d", h), bucket[h]));
        }

        long arrToday = arrivalRepo.count(arrivalSpec(tStart, tEnd, null));
        long arrYesterday = arrivalRepo.count(arrivalSpec(yStart, tStart, null));
        long treatToday = arrivalRepo.count(arrivalSpec(tStart, tEnd, List.of("TRIAGED", "CALLED")));
        long treatYesterday = arrivalRepo.count(arrivalSpec(yStart, tStart, List.of("TRIAGED", "CALLED")));
        long apptToday = apptRepo.count(apptSpec(today));
        long apptYesterday = apptRepo.count(apptSpec(yesterday));

        List<KpiView> kpis = List.of(
                new KpiView("revenue", "今日营收", revToday, true, "元", deltaPct(revToday, revYesterday)),
                new KpiView("arrival", "今日到店", arrToday, false, "人", deltaPct(arrToday, arrYesterday)),
                new KpiView("paidCount", "今日成交单", cntToday, false, "单", deltaPct(cntToday, cntYesterday)),
                new KpiView("avgTicket", "客单价", avgToday, true, "元",
                        avgYesterday == null ? null : deltaPct(avgToday, avgYesterday)),
                new KpiView("inTreat", "在院治疗", treatToday, false, "人", deltaPct(treatToday, treatYesterday)),
                new KpiView("appt", "今日预约", apptToday, false, "人", deltaPct(apptToday, apptYesterday)));

        List<CategoryShareView> categoryShare = categoryShare(paidToday);

        Map<String, Long> byStore = new HashMap<>();
        for (TxnOrder o : paidToday) {
            if (o.getStoreCode() != null && !o.getStoreCode().isBlank() && o.getAmount() != null) {
                byStore.merge(o.getStoreCode(), o.getAmount(), Long::sum);
            }
        }
        List<StoreRankView> storeRanks = byStore.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .map(e -> new StoreRankView(e.getKey(), e.getValue()))
                .toList();

        List<String> notes = List.of(
                "口径：Asia/Shanghai 自然日；营收/成交单/客单价 = status「已收款」订单按创建时刻落日（非支付完成时点）",
                "较昨日 = 与昨日同口径对比；昨日为 0 时显示「—」（null，不伪造箭头）",
                "品类占比 = 当日已收款订单子项金额按 SKU 服务大类归桶；子项名未命中 SKU 目录（含售卡单卡项名）归「其他」",
                "门店排行 = 当日已收款金额 top5；门店名由前端经 /api/stores 映射",
                "在院治疗 = 当日到店登记中状态 TRIAGED/CALLED 行数");

        return new ScreenOverviewView(kpis, hourly, categoryShare, storeRanks, notes);
    }

    /** 品类占比：已收款订单子项金额按服务大类归桶，占比整数四舍五入（总和可能 ≠100，如实）。 */
    private List<CategoryShareView> categoryShare(List<TxnOrder> paidToday) {
        if (paidToday.isEmpty()) {
            return List.of();
        }
        List<String> orderNos = paidToday.stream().map(TxnOrder::getOrderNo).toList();
        Map<String, String> skuCat = projectClient.skuCategoryMap();
        Map<String, Long> byCat = new LinkedHashMap<>();
        long total = 0L;
        for (OrderItem it : itemRepo.findByOrderNoIn(orderNos)) {
            long amt = it.getAmount() == null ? 0L : it.getAmount();
            total += amt;
            String cat = skuCat.getOrDefault(it.getItemName(), "");
            byCat.merge(CATEGORY_LABEL.getOrDefault(cat, CATEGORY_OTHER), amt, Long::sum);
        }
        if (total <= 0L) {
            return List.of();
        }
        final long t = total;
        return byCat.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .map(e -> new CategoryShareView(e.getKey(), Math.round(e.getValue() * 100.0 / t)))
                .toList();
    }

    /** 较昨日百分比（1 位小数）；昨日为 0 → null（前端不显示箭头）。 */
    private static Double deltaPct(long today, long yesterday) {
        if (yesterday == 0L) {
            return null;
        }
        return Math.round((today - yesterday) * 1000.0 / yesterday) / 10.0;
    }

    private static long sumAmount(List<TxnOrder> orders) {
        long sum = 0L;
        for (TxnOrder o : orders) {
            if (o.getAmount() != null) {
                sum += o.getAmount();
            }
        }
        return sum;
    }

    private Specification<TxnOrder> paidSpec(OffsetDateTime start, OffsetDateTime end) {
        return Specification.where(DataScope.<TxnOrder>storeSpec("storeCode")).and((root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), "已收款"));
            ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            ps.add(cb.lessThan(root.get("createdAt"), end));
            return cb.and(ps.toArray(new Predicate[0]));
        });
    }

    private Specification<Arrival> arrivalSpec(OffsetDateTime start, OffsetDateTime end, List<String> statuses) {
        return Specification.where(DataScope.<Arrival>storeSpec("storeCode")).and((root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.greaterThanOrEqualTo(root.get("arrivedAt"), start));
            ps.add(cb.lessThan(root.get("arrivedAt"), end));
            if (statuses != null) {
                ps.add(root.get("status").in(statuses));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        });
    }

    private Specification<Appointment> apptSpec(LocalDate day) {
        return Specification.where(DataScope.<Appointment>storeSpec("storeCode")).and((root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("apptDate"), day));
            ps.add(cb.notEqual(root.get("status"), "已取消"));
            return cb.and(ps.toArray(new Predicate[0]));
        });
    }

    /** 大屏快照视图。金额统一「分」：KPI fen=true 行 value 单位为分（前端 /100 换算元）。 */
    public record ScreenOverviewView(List<KpiView> kpis, List<HourlyPoint> hourly,
                                     List<CategoryShareView> categoryShare, List<StoreRankView> storeRanks,
                                     List<String> notes) {
    }

    public record KpiView(String key, String label, long value, boolean fen, String unit, Double deltaPct) {
    }

    public record HourlyPoint(String hour, long amountFen) {
    }

    public record CategoryShareView(String name, long pct) {
    }

    public record StoreRankView(String storeCode, long amountFen) {
    }
}
