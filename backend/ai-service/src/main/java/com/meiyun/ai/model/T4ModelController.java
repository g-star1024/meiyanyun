package com.meiyun.ai.model;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * T4 模型仓库业务出口（/ai/models 模型管理 Tab）。
 * 类级权限 model:view；写端点方法级细码（拦截语义为方法级覆盖类级）：
 * register=model:register / versions=model:version / release-request=model:release /
 * rollback=model:rollback / deprecate 挂 model:release（权限矩阵零新码，契约④）。
 * 发布红线：仅 READY 版本可提交发布申请，由服务端直连审批中心登记 T4_MODEL 单。
 */
@RestController
@RequestMapping("/api/ai/t4/models")
@RequirePerm("model:view")
public class T4ModelController {

    private final T4ModelService t4ModelService;

    public T4ModelController(T4ModelService t4ModelService) {
        this.t4ModelService = t4ModelService;
    }

    @GetMapping
    public List<T4ModelService.ModelView> list() {
        return t4ModelService.list();
    }

    @PostMapping
    @RequirePerm("model:register")
    public T4ModelService.ModelView register(@RequestBody T4ModelService.RegisterCmd cmd) {
        return t4ModelService.register(cmd, DataScope.currentActor());
    }

    @PostMapping("/{code}/versions")
    @RequirePerm("model:version")
    public T4ModelService.ModelView addVersion(@PathVariable String code,
                                               @RequestBody T4ModelService.VersionCmd cmd) {
        return t4ModelService.addVersion(code, cmd, DataScope.currentActor());
    }

    @PostMapping("/{code}/release-request")
    @RequirePerm("model:release")
    public T4ModelService.ReleaseResult requestRelease(@PathVariable String code,
                                                       @RequestBody T4ModelService.ReleaseCmd cmd) {
        return t4ModelService.requestRelease(code, cmd, DataScope.currentActor());
    }

    @PostMapping("/{code}/rollback")
    @RequirePerm("model:rollback")
    public T4ModelService.ModelView rollback(@PathVariable String code,
                                             @RequestBody T4ModelService.RollbackCmd cmd) {
        return t4ModelService.rollback(code, cmd, DataScope.currentActor());
    }

    @PostMapping("/{code}/deprecate")
    @RequirePerm("model:release")
    public T4ModelService.ModelView deprecate(@PathVariable String code) {
        return t4ModelService.deprecate(code, DataScope.currentActor());
    }
}
