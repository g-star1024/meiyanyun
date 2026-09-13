package com.meiyun.ai.repurchase;

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
 * 复购预测引擎（A1-03）业务出口。
 * 面向网关业务角色，类级仅需 aiRepurchase:view；能否真正出站由 repurchase 功能的
 * ai_feature_role 灰度矩阵在 FeatureInvokeService 内判定，运行预测/建跟进/推送动作经审计链留痕。
 */
@RestController
@RequestMapping("/api/ai/repurchase")
@RequirePerm("aiRepurchase:view")
public class RepurchaseController {

    private final RepurchaseService repurchaseService;

    public RepurchaseController(RepurchaseService repurchaseService) {
        this.repurchaseService = repurchaseService;
    }

    /** 运行预测：逐候选真实出站，聚成一批落库。 */
    @PostMapping("/run")
    public RepurchaseService.BatchView run(@RequestBody RepurchaseService.RunCmd cmd) {
        return repurchaseService.run(cmd);
    }

    /** 当前榜单：选定周期最近批次，支持 projectCode 品类筛选（all=全部）。 */
    @GetMapping("/list")
    public List<RepurchaseService.RepurchaseView> list(@RequestParam(defaultValue = "week") String period,
                                                       @RequestParam(required = false) String projectCode) {
        return repurchaseService.list(period, projectCode);
    }

    /** 当前批次元信息。 */
    @GetMapping("/batch")
    public RepurchaseService.BatchView batch(@RequestParam(defaultValue = "week") String period) {
        return repurchaseService.currentBatch(period);
    }

    /** 4 KPI 真实聚合。 */
    @GetMapping("/stats")
    public RepurchaseService.RepurchaseStats stats(@RequestParam(defaultValue = "week") String period) {
        return repurchaseService.stats(period);
    }

    /** Top3 推荐依据（真实信号与暂无数据源项如实区分）。 */
    @GetMapping("/factors")
    public RepurchaseService.FactorModelView factors() {
        return repurchaseService.factors();
    }

    /** 登记建跟进（站内幂等；M3-08 真实下发为远期 Backlog）。 */
    @PostMapping("/{id}/followup")
    public RepurchaseService.ActionResult followup(@PathVariable Long id) {
        return repurchaseService.registerFollowup(id);
    }

    /** 登记推送（站内幂等；M5-03 真实下发为远期 Backlog）。 */
    @PostMapping("/{id}/push")
    public RepurchaseService.ActionResult push(@PathVariable Long id) {
        return repurchaseService.registerPush(id);
    }

    /** 当前批次批量登记建跟进（幂等）。 */
    @PostMapping("/batch-followup")
    public RepurchaseService.BatchFollowupResult batchFollowup(@RequestParam(defaultValue = "week") String period) {
        return repurchaseService.batchFollowup(period);
    }
}
