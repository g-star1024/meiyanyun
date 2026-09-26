package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B99 转化漏斗客户级重写：五级口径（线索=有效预约[ finance 侧再合并 landing 留资] →
 * 到院=arrival 渠道级真实 → 咨询=consult_plan arrival_id 直链客户级 →
 * 成交=txn_order 已收款/已核销客户级 → 复购=区间 paid≥2 客户）。
 * 区间为 [from,to) 双闭（R03 采集器本传半闭，修复历史 +1 天瑕疵）；渠道无直链归 UNKNOWN 如实单列，不广播回填。
 * rows 结构 {storeCode,channel,arrivalCount,consultCount,dealCount} 保留（R03 采集器消费不破）。
 */
@RestController
@RequestMapping("/api/txn/internal")
public class InternalFunnelController {

    private final ArrivalRepository arrivalRepo;
    private final PlanRepository planRepo;
    private final AppointmentRepository apptRepo;
    private final TxnOrderRepository txnRepo;

    public InternalFunnelController(ArrivalRepository arrivalRepo, PlanRepository planRepo,
                                    AppointmentRepository apptRepo, TxnOrderRepository txnRepo) {
        this.arrivalRepo = arrivalRepo;
        this.planRepo = planRepo;
        this.apptRepo = apptRepo;
        this.txnRepo = txnRepo;
    }

    @GetMapping("/funnel-stats")
    @RequirePerm("internal:finance-flow")
    public Map<String, Object> funnelStats(
            @RequestParam("from") String from,
            @RequestParam("to") String to) {

        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        OffsetDateTime fromTime = fromDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        OffsetDateTime toTime = toDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();

        List<Object[]> leadRows = apptRepo.funnelLeadAppts(fromDate, toDate);
        List<Object[]> arrivals = arrivalRepo.funnelArrivals(fromTime, toTime);
        List<Object[]> arrivalLinks = arrivalRepo.funnelArrivalLinks(fromTime, toTime);
        List<Object[]> consultRows = planRepo.funnelConsultRows(fromTime, toTime);
        List<Object[]> paidRows = txnRepo.funnelPaidOrderRows(fromTime, toTime);
        List<Object[]> consultantRank = planRepo.funnelConsultantRank(fromTime, toTime);

        Map<String, String> keyByAhNo = new HashMap<>();
        for (Object[] a : arrivalLinks) {
            keyByAhNo.put((String) a[0], a[1] + "|" + (a[2] != null ? a[2] : "UNKNOWN"));
        }

        Map<String, Long> arrivalCountByKey = new LinkedHashMap<>();
        long arriveTotal = 0;
        for (Object[] a : arrivals) {
            String key = a[0] + "|" + (a[1] != null ? a[1] : "UNKNOWN");
            long cnt = ((Number) a[2]).longValue();
            arrivalCountByKey.merge(key, cnt, Long::sum);
            arriveTotal += cnt;
        }

        Map<String, String> keyByCustomer = new LinkedHashMap<>();
        Set<String> consultGlobal = new LinkedHashSet<>();
        for (Object[] c : consultRows) {
            String cid = (String) c[0];
            String ahId = (String) c[1];
            String sc = (String) c[2];
            if (cid == null) {
                continue;
            }
            consultGlobal.add(cid);
            String key = (ahId != null && keyByAhNo.containsKey(ahId)) ? keyByAhNo.get(ahId) : sc + "|UNKNOWN";
            keyByCustomer.putIfAbsent(cid, key);
        }

        Map<String, Integer> paidCountByCid = new HashMap<>();
        Map<String, Map<String, Integer>> paidCountByCidStore = new HashMap<>();
        Map<String, String> storeByCid = new HashMap<>();
        Set<String> dealGlobal = new LinkedHashSet<>();
        for (Object[] p : paidRows) {
            String cid = (String) p[0];
            String sc = (String) p[1];
            if (cid == null) {
                continue;
            }
            dealGlobal.add(cid);
            paidCountByCid.merge(cid, 1, Integer::sum);
            storeByCid.putIfAbsent(cid, sc);
            if (sc != null) {
                paidCountByCidStore.computeIfAbsent(cid, k -> new HashMap<>()).merge(sc, 1, Integer::sum);
            }
        }
        long repurchase = paidCountByCid.values().stream().filter(n -> n >= 2).count();

        Map<String, Set<String>> consultByKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : keyByCustomer.entrySet()) {
            consultByKey.computeIfAbsent(e.getValue(), k -> new LinkedHashSet<>()).add(e.getKey());
        }
        Map<String, Set<String>> dealByKey = new LinkedHashMap<>();
        for (String cid : dealGlobal) {
            String key = keyByCustomer.getOrDefault(cid, storeByCid.get(cid) + "|UNKNOWN");
            dealByKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(cid);
        }

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(arrivalCountByKey.keySet());
        allKeys.addAll(consultByKey.keySet());
        allKeys.addAll(dealByKey.keySet());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : allKeys) {
            String[] parts = key.split("\\|", 2);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("storeCode", parts[0]);
            row.put("channel", parts.length > 1 ? parts[1] : "UNKNOWN");
            row.put("arrivalCount", arrivalCountByKey.getOrDefault(key, 0L));
            row.put("consultCount", (long) consultByKey.getOrDefault(key, Set.of()).size());
            row.put("dealCount", (long) dealByKey.getOrDefault(key, Set.of()).size());
            rows.add(row);
        }

        List<Map<String, Object>> consultants = new ArrayList<>();
        for (Object[] r : consultantRank) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("consultantId", r[0]);
            c.put("consult", ((Number) r[1]).longValue());
            c.put("deal", ((Number) r[2]).longValue());
            c.put("amountFen", ((Number) r[3]).longValue());
            consultants.add(c);
        }

        long leadTotal = 0;
        for (Object[] l : leadRows) {
            leadTotal += ((Number) l[1]).longValue();
        }

        Map<String, long[]> byStore = new LinkedHashMap<>(); // 0lead 1arrive 2consult 3deal 4repurchase
        for (Object[] l : leadRows) {
            String sc = (String) l[0];
            if (sc != null) {
                byStore.computeIfAbsent(sc, k -> new long[5])[0] += ((Number) l[1]).longValue();
            }
        }
        for (Map.Entry<String, Long> e : arrivalCountByKey.entrySet()) {
            byStore.computeIfAbsent(e.getKey().split("\\|", 2)[0], k -> new long[5])[1] += e.getValue();
        }
        for (Map.Entry<String, Set<String>> e : consultByKey.entrySet()) {
            byStore.computeIfAbsent(e.getKey().split("\\|", 2)[0], k -> new long[5])[2] += e.getValue().size();
        }
        for (Map.Entry<String, Set<String>> e : dealByKey.entrySet()) {
            byStore.computeIfAbsent(e.getKey().split("\\|", 2)[0], k -> new long[5])[3] += e.getValue().size();
        }
        for (Map.Entry<String, Map<String, Integer>> e : paidCountByCidStore.entrySet()) {
            for (Map.Entry<String, Integer> s : e.getValue().entrySet()) {
                if (s.getValue() >= 2) {
                    byStore.computeIfAbsent(s.getKey(), k -> new long[5])[4] += 1;
                }
            }
        }
        List<Map<String, Object>> stagesByStore = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byStore.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("storeCode", e.getKey());
            s.put("lead", e.getValue()[0]);
            s.put("arrive", e.getValue()[1]);
            s.put("consult", e.getValue()[2]);
            s.put("deal", e.getValue()[3]);
            s.put("repurchase", e.getValue()[4]);
            stagesByStore.add(s);
        }

        Map<String, Object> stages = new LinkedHashMap<>();
        stages.put("lead", leadTotal);
        stages.put("arrive", arriveTotal);
        stages.put("consult", (long) consultGlobal.size());
        stages.put("deal", (long) dealGlobal.size());
        stages.put("repurchase", repurchase);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("stages", stages);
        result.put("stagesByStore", stagesByStore);
        result.put("consultants", consultants);
        return result;
    }
}
