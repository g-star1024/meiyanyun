package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3-B7 卡1：客户洞察报告 M3-19 切真（DESIGN-M3 §3 D5）。
 * 只读聚合：summary 七键＋近 6 月趋势＋等级/渠道分布＋智能洞察四条＋高复购 TOP5；
 * 零新表、零新权限码（insight:view 已预埋）、限时段（period 30d/90d/12m 默认 90d）。
 */
@RestController
@RequestMapping("/api/customer/m3/insight")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping
    @RequirePerm("insight:view")
    public InsightService.InsightView view(@RequestParam(name = "period", defaultValue = "90d") String period) {
        return insightService.view(period);
    }
}
