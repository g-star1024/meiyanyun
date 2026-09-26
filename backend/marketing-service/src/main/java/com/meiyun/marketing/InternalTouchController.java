package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * B99 internal：触点事件区间计数（转化漏斗线索级 LANDING_LEAD 留资，供 finance 聚合层合并 lead）。
 * 权限码复用 internal:finance-flow（X-Internal-Token 直通；audit InternalComplianceController 跨服务复用先例）。
 * 区间 [from,to) 双闭，与 txn funnel-stats 对齐。
 */
@RestController
@RequestMapping("/api/marketing/internal")
public class InternalTouchController {

    private final TouchEventRepository touchRepo;

    public InternalTouchController(TouchEventRepository touchRepo) {
        this.touchRepo = touchRepo;
    }

    @GetMapping("/touch-events/lead-count")
    @RequirePerm("internal:finance-flow")
    public Map<String, Object> leadCount(@RequestParam("from") String from, @RequestParam("to") String to) {
        OffsetDateTime fromTime = LocalDate.parse(from).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        OffsetDateTime toTime = LocalDate.parse(to).atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
        return Map.of("leadCount", touchRepo.countLeads(fromTime, toTime));
    }
}
