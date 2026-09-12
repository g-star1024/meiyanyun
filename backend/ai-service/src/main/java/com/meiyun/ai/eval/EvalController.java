package com.meiyun.ai.eval;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * AI 效果评估与 A/B 实验（A1Govern 效果评估 / A·B 实验 Tab）。
 * 查看 aiGovern:view；创建/刷新/结案 aiAdmin:edit（管理员发起、治理岗查看）。
 */
@RestController
@RequestMapping("/api/ai")
@RequirePerm("aiGovern:view")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    // -------------------- 效果评估 --------------------

    @GetMapping("/evals")
    public List<EvalService.EvalView> listTasks() {
        return evalService.listTasks();
    }

    @PostMapping("/evals")
    @RequirePerm("aiAdmin:edit")
    public EvalService.EvalView createTask(@RequestBody EvalService.EvalCreateCmd cmd) {
        return evalService.createTask(cmd, DataScope.currentActor());
    }

    @PostMapping("/evals/{id}/refresh")
    @RequirePerm("aiAdmin:edit")
    public EvalService.EvalView refreshTask(@PathVariable Long id) {
        return evalService.refreshTask(id, DataScope.currentActor());
    }

    @PostMapping("/evals/{id}/conclude")
    @RequirePerm("aiAdmin:edit")
    public EvalService.EvalView concludeTask(@PathVariable Long id,
                                             @RequestBody EvalService.ConclusionCmd cmd) {
        return evalService.concludeTask(id, cmd, DataScope.currentActor());
    }

    // -------------------- A/B 实验 --------------------

    @GetMapping("/experiments")
    public List<EvalService.ExperimentView> listExperiments() {
        return evalService.listExperiments();
    }

    @PostMapping("/experiments")
    @RequirePerm("aiAdmin:edit")
    public EvalService.ExperimentView createExperiment(@RequestBody EvalService.ExperimentCreateCmd cmd) {
        return evalService.createExperiment(cmd, DataScope.currentActor());
    }

    @PostMapping("/experiments/{id}/refresh")
    @RequirePerm("aiAdmin:edit")
    public EvalService.ExperimentView refreshExperiment(@PathVariable Long id) {
        return evalService.refreshExperiment(id, DataScope.currentActor());
    }

    @PostMapping("/experiments/{id}/conclude")
    @RequirePerm("aiAdmin:edit")
    public EvalService.ExperimentView concludeExperiment(@PathVariable Long id,
                                                         @RequestBody EvalService.ConclusionCmd cmd) {
        return evalService.concludeExperiment(id, cmd, DataScope.currentActor());
    }

    // -------------------- KPI 汇总 --------------------

    @GetMapping("/eval-stats")
    public Map<String, Object> stats() {
        BigDecimal avgLift = evalService.avgLiftPp();
        return Map.of(
                "runningExperiments", evalService.runningExperimentCount(),
                "avgLiftPp", avgLift == null ? "" : avgLift);
    }
}
