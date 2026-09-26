package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3-B4 卡1：客户旅程 M3-07 切真（DESIGN-M3 §3 D2/D5/D6）。
 * 只读聚合时间轴：六阶段（预约/到店/咨询/支付/回访/复购）＋触达风险分级；
 * 零新表、零新权限码（journey:view 已预埋）、限时段（days 默认 90）＋ limit 截断。
 */
@RestController
@RequestMapping("/api/customer/m3/journey")
public class JourneyController {

    private final JourneyService journeyService;

    public JourneyController(JourneyService journeyService) {
        this.journeyService = journeyService;
    }

    @GetMapping
    @RequirePerm("journey:view")
    public JourneyService.JourneyView view(@RequestParam(name = "days", defaultValue = "90") int days,
                                           @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return journeyService.view(days, limit);
    }
}
