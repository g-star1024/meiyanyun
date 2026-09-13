package com.meiyun.ai.script;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能话术库业务出口。
 * 面向网关业务角色，类级仅需 aiGateway:view；AI 生成能否真正出站由 scripts 功能的
 * ai_feature_role 灰度矩阵在 FeatureInvokeService 内判定，新增/编辑/采纳/反馈均经审计链留痕。
 */
@RestController
@RequestMapping("/api/ai/scripts")
@RequirePerm("aiGateway:view")
public class ScriptController {

    private final ScriptService scriptService;

    public ScriptController(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @GetMapping
    public Page<ScriptService.ScriptView> list(@RequestParam(required = false) String scene,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return scriptService.list(scene, keyword, page, size);
    }

    @GetMapping("/stats")
    public ScriptService.ScriptStats stats() {
        return scriptService.stats();
    }

    @PostMapping("/generate")
    public ScriptService.ScriptGenView generate(@RequestBody ScriptService.GenerateCmd cmd) {
        return scriptService.generate(cmd);
    }

    @PostMapping
    public ScriptService.ScriptView create(@RequestBody ScriptService.SaveCmd cmd) {
        return scriptService.create(cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}")
    public ScriptService.ScriptView update(@PathVariable Long id,
                                           @RequestBody ScriptService.SaveCmd cmd) {
        return scriptService.update(id, cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}/adopt")
    public ScriptService.ActionResult adopt(@PathVariable Long id) {
        return scriptService.adopt(id, DataScope.currentActor());
    }

    @PostMapping("/{id}/feedback")
    public ScriptService.ActionResult feedback(@PathVariable Long id) {
        return scriptService.feedback(id, DataScope.currentActor());
    }
}
