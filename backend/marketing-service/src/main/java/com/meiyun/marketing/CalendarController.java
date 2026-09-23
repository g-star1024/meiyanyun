package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 营销日历端点（P5-B88）。
 * 类级 calendar:view 覆盖查询；新建排期方法级 calendar:edit（区域级权限，矩阵已播种零新增）。
 * 挂 /api/marketing 同前缀——既有网关 /api 转发规则直接覆盖，零改动。
 */
@RestController
@RequestMapping("/api/marketing")
@RequirePerm("calendar:view")
public class CalendarController {

    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/calendar/nodes")
    public List<CalendarNode> nodes() {
        return calendarService.listNodes();
    }

    @GetMapping("/calendar/schedules")
    public List<CalendarSchedule> schedules() {
        return calendarService.listSchedules();
    }

    /** 新建排期（落 SCHEDULED）；校验链、敏感词拦截、client_token 幂等与审计在 {@link CalendarService}。 */
    @PostMapping("/calendar/schedules")
    @RequirePerm("calendar:edit")
    public CalendarSchedule createSchedule(@RequestBody CalendarService.ScheduleCmd cmd) {
        return calendarService.create(cmd);
    }
}
