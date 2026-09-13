package com.meiyun.ai.scheduling;

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
 * AI 智能排班（A1-08）业务出口。
 * 面向网关业务角色，类级仅需 aiScheduling:view；排班解读能否真正出站由 scheduling 功能的
 * ai_feature_role 角色灰度矩阵与 ai_feature_binding 门店灰度在 FeatureInvokeService 内判定。
 * 客流/员工/成本均为真实跨服务数据与明示规则参数，空库即排班页诚实空态。
 */
@RestController
@RequestMapping("/api/ai/scheduling")
@RequirePerm("aiScheduling:view")
public class SchedulingController {

    private final SchedulingService schedulingService;

    public SchedulingController(SchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    /** 生成（或重生成）指定周排班方案：真实员工池+历史到店规则矩阵，scheduling invoke 生成解读。 */
    @PostMapping("/generate")
    public SchedulingService.PlanView generate(@RequestBody(required = false) SchedulingService.GenerateCmd cmd) {
        return schedulingService.generate(cmd);
    }

    /** 指定周最新版方案（甘特矩阵/槽位/解读）；无方案 404 中文引导。 */
    @GetMapping("/plan")
    public SchedulingService.PlanView plan(@RequestParam(required = false) String weekStart,
                                           @RequestParam(required = false) String storeCode) {
        return schedulingService.plan(weekStart, storeCode);
    }

    /** 采纳方案：站内幂等状态翻转（M2-03 真实回填为远期 Backlog）。 */
    @PostMapping("/plans/{id}/adopt")
    public SchedulingService.ActionResult adopt(@PathVariable Long id) {
        return schedulingService.adopt(id);
    }

    /** 页头 KPI：方案数/采纳数/本周 scheduling invoke + 诚实模型说明。 */
    @GetMapping("/stats")
    public SchedulingService.SchedulingStats stats() {
        return schedulingService.stats();
    }

    /** 历史方案：按周归并取最新版，最多 14 条。 */
    @GetMapping("/history")
    public List<SchedulingService.HistoryItem> history(@RequestParam(required = false) String storeCode) {
        return schedulingService.history(storeCode);
    }
}
