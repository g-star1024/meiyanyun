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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/txn/internal")
public class InternalFunnelController {

    private final ArrivalRepository arrivalRepo;
    private final PlanRepository planRepo;

    public InternalFunnelController(ArrivalRepository arrivalRepo, PlanRepository planRepo) {
        this.arrivalRepo = arrivalRepo;
        this.planRepo = planRepo;
    }

    @GetMapping("/funnel-stats")
    @RequirePerm("internal:finance-flow")
    public Map<String, Object> funnelStats(
            @RequestParam("from") String from,
            @RequestParam("to") String to) {

        OffsetDateTime fromTime = LocalDate.parse(from).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        OffsetDateTime toTime = LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();

        List<Object[]> arrivals = arrivalRepo.funnelArrivals(fromTime, toTime);
        List<Object[]> consults = planRepo.funnelConsults(fromTime, toTime);
        List<Object[]> deals = planRepo.funnelDeals(fromTime, toTime);

        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Object[] a : arrivals) {
            String sc = (String) a[0];
            String ch = (String) a[1];
            long cnt = (Long) a[2];
            String key = sc + "|" + ch;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("storeCode", sc);
            row.put("channel", ch);
            row.put("arrivalCount", cnt);
            row.put("consultCount", 0L);
            row.put("dealCount", 0L);
            rows.put(key, row);
        }
        for (Object[] c : consults) {
            String sc = (String) c[0];
            long cnt = (Long) c[1];
            for (Map<String, Object> row : rows.values()) {
                if (sc.equals(row.get("storeCode"))) {
                    row.put("consultCount", cnt);
                }
            }
        }
        for (Object[] d : deals) {
            String sc = (String) d[0];
            long cnt = (Long) d[1];
            for (Map<String, Object> row : rows.values()) {
                if (sc.equals(row.get("storeCode"))) {
                    row.put("dealCount", cnt);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", new ArrayList<>(rows.values()));
        return result;
    }
}
