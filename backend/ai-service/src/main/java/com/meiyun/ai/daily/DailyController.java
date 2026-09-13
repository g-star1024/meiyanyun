package com.meiyun.ai.daily;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 经营日报（A1-05）业务出口。
 * 面向网关业务角色，类级仅需 aiDaily:view；能否真正出站由 daily 功能的
 * ai_feature_role 灰度矩阵在 FeatureInvokeService 内判定，生成日报/订阅/采纳动作经审计链留痕。
 */
@RestController
@RequestMapping("/api/ai/daily")
@RequirePerm("aiDaily:view")
public class DailyController {

    private final DailyService dailyService;

    public DailyController(DailyService dailyService) {
        this.dailyService = dailyService;
    }

    /** 当日（或指定日期）真实经营 KPI 与环比：交易域投影，不依赖是否已生成日报。 */
    @GetMapping("/metrics")
    public DailyService.MetricsView metrics(@RequestParam(required = false) String date,
                                            @RequestParam(required = false) String storeCode) {
        return dailyService.metrics(date, storeCode);
    }

    /** 生成日报：拉真实指标 → daily invoke → 容错解析落库（同日期+门店可重生成，读时取最新版）。 */
    @PostMapping("/generate")
    public DailyService.GenerateResult generate(@RequestBody(required = false) DailyService.GenerateCmd cmd) {
        return dailyService.generate(cmd);
    }

    /** 指定日期最新版日报（含摘要与建议）；无报告 404 引导先生成。 */
    @GetMapping("/report")
    public DailyService.ReportView report(@RequestParam(required = false) String date,
                                          @RequestParam(required = false) String storeCode) {
        return dailyService.report(date, storeCode);
    }

    /** 历史日报：按日期+门店归并最新版，日期倒序最多 14 条。 */
    @GetMapping("/history")
    public List<DailyService.HistoryItem> history(@RequestParam(required = false) String storeCode) {
        return dailyService.history(storeCode);
    }

    /** 页头统计：报告/建议/采纳真实计数 + 本周 daily invoke + 诚实模型说明。 */
    @GetMapping("/stats")
    public DailyService.DailyStats stats() {
        return dailyService.stats();
    }

    /** 推送通道能力：本期无真实定时出站，站内/企微/短信/邮件四通道如实置灰。 */
    @GetMapping("/channels")
    public List<DailyService.ChannelView> channels() {
        return dailyService.channels();
    }

    /** 当前登录员工的日报订阅偏好（无记录默认未订阅）。 */
    @GetMapping("/subscription")
    public DailyService.SubscriptionView subscription() {
        return dailyService.subscription();
    }

    /** 切换订阅偏好（员工级 upsert，审计留痕；本期仅登记，无真实推送）。 */
    @PostMapping("/subscription")
    public DailyService.SubscriptionView toggleSubscription(@RequestBody(required = false) SubscriptionCmd cmd) {
        return dailyService.toggleSubscription(cmd == null ? null : cmd.subscribed());
    }

    /** 采纳建议（站内幂等登记）；真实任务下发为远期 Backlog。 */
    @PostMapping("/suggestions/{id}/adopt")
    public DailyService.ActionResult adopt(@PathVariable Long id) {
        return dailyService.adopt(id);
    }

    public record SubscriptionCmd(Boolean subscribed) {
    }
}
