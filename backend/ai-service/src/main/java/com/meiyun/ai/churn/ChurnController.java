package com.meiyun.ai.churn;

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
 * 流失预警引擎（A1-09）业务出口。
 * 面向网关业务角色，类级仅需 aiChurn:view；能否真正出站由 churn 功能的
 * ai_feature_role 灰度矩阵在 FeatureInvokeService 内判定，运行评分/登记干预动作经审计链留痕。
 */
@RestController
@RequestMapping("/api/ai/churn")
@RequirePerm("aiChurn:view")
public class ChurnController {

    private final ChurnService churnService;

    public ChurnController(ChurnService churnService) {
        this.churnService = churnService;
    }

    /** 运行评分：逐候选真实出站，聚成一批落库（最近一批即当前榜，无周期维度）。 */
    @PostMapping("/run")
    public ChurnService.BatchView run(@RequestBody(required = false) ChurnService.RunCmd cmd) {
        return churnService.run(cmd);
    }

    /** 当前风险榜：最近批次，支持 riskLevel=high/mid/low 筛选（all=全部）。 */
    @GetMapping("/list")
    public List<ChurnService.ChurnView> list(@RequestParam(required = false) String riskLevel) {
        return churnService.list(riskLevel);
    }

    /** 当前批次元信息。 */
    @GetMapping("/batch")
    public ChurnService.BatchView batch() {
        return churnService.currentBatch();
    }

    /** 4 KPI 真实聚合（模型效果字段诚实标注待回流评估）。 */
    @GetMapping("/stats")
    public ChurnService.ChurnStats stats() {
        return churnService.stats();
    }

    /** 流失因子模型（真实信号与暂无数据源项如实区分）。 */
    @GetMapping("/factors")
    public ChurnService.FactorModelView factors() {
        return churnService.factors();
    }

    /** 登记干预（站内幂等；M3-10/M2-17/M5-03 真实下发为远期 Backlog）。 */
    @PostMapping("/{id}/intervene")
    public ChurnService.ActionResult intervene(@PathVariable Long id) {
        return churnService.registerIntervene(id);
    }

    /** 当前批次批量登记干预（幂等：仅未登记行受影响）。 */
    @PostMapping("/batch-intervene")
    public ChurnService.BatchInterveneResult batchIntervene() {
        return churnService.batchIntervene();
    }
}
