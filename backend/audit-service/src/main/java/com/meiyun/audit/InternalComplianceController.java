package com.meiyun.audit;

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
@RequestMapping("/api/audit/internal")
public class InternalComplianceController {

    private final ComplianceCheckRepository checkRepo;

    public InternalComplianceController(ComplianceCheckRepository checkRepo) {
        this.checkRepo = checkRepo;
    }

    @GetMapping("/compliance-stats")
    @RequirePerm("internal:finance-flow")
    public Map<String, Object> complianceStats(
            @RequestParam("from") String from,
            @RequestParam("to") String to) {

        OffsetDateTime fromTime = LocalDate.parse(from).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        OffsetDateTime toTime = LocalDate.parse(to).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();

        List<Object[]> rows = checkRepo.complianceStats(fromTime, toTime);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("storeName", r[0]);
            row.put("category", r[1]);
            row.put("totalCount", r[2]);
            row.put("passCount", r[3]);
            row.put("warnCount", r[4]);
            row.put("failCount", r[5]);
            row.put("pendingCount", r[6]);
            row.put("remediatedCount", r[7]);
            result.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", result);
        return out;
    }
}
