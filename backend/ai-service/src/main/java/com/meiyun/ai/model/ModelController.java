package com.meiyun.ai.model;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/models")
@RequirePerm("aiAdmin:view")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping
    public List<ModelService.ModelView> list(@RequestParam(required = false) Long providerId) {
        return modelService.list(providerId);
    }

    @GetMapping("/{id}")
    public ModelService.ModelView get(@PathVariable Long id) {
        return modelService.get(id);
    }

    @PostMapping
    @RequirePerm("aiAdmin:edit")
    public ModelService.SaveResult create(@RequestBody ModelService.ModelCmd cmd) {
        return modelService.create(cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}")
    @RequirePerm("aiAdmin:edit")
    public ModelService.SaveResult update(@PathVariable Long id,
                                          @RequestBody ModelService.ModelCmd cmd) {
        return modelService.update(id, cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}/delete")
    @RequirePerm("aiAdmin:edit")
    public Map<String, Object> delete(@PathVariable Long id) {
        ModelService.SaveResult r = modelService.delete(id, DataScope.currentActor());
        return Map.of("changed", r.changed());
    }

    /** 真实连通性测试：打一次供应商 /chat/completions 并回写连通状态。 */
    @PostMapping("/{id}/test")
    @RequirePerm("aiAdmin:edit")
    public ModelService.TestResult test(@PathVariable Long id) {
        return modelService.testConnection(id, DataScope.currentActor());
    }
}
