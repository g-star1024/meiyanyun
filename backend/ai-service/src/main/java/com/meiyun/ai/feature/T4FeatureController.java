package com.meiyun.ai.feature;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T4 特征平台业务出口（/ai/features）。
 * 类级权限 feature:view；写端点方法级细码（拦截语义为方法级覆盖类级）：
 * register=feature:register / publish+deprecate=feature:publish / serving=feature:edit
 * （权限矩阵零新码，契约④）。
 */
@RestController
@RequestMapping("/api/ai/t4/features")
@RequirePerm("feature:view")
public class T4FeatureController {

    private final T4FeatureService t4FeatureService;

    public T4FeatureController(T4FeatureService t4FeatureService) {
        this.t4FeatureService = t4FeatureService;
    }

    @GetMapping
    public T4FeatureService.FeatureOverview overview() {
        return t4FeatureService.overview();
    }

    @PostMapping("/register")
    @RequirePerm("feature:register")
    public T4FeatureService.FeatureView register(@RequestBody T4FeatureService.RegisterCmd cmd) {
        return t4FeatureService.register(cmd, DataScope.currentActor());
    }

    @PostMapping("/{code}/publish")
    @RequirePerm("feature:publish")
    public T4FeatureService.FeatureView publish(@PathVariable String code) {
        return t4FeatureService.publish(code, DataScope.currentActor());
    }

    @PostMapping("/{code}/deprecate")
    @RequirePerm("feature:publish")
    public T4FeatureService.FeatureView deprecate(@PathVariable String code) {
        return t4FeatureService.deprecate(code, DataScope.currentActor());
    }

    @PostMapping("/{code}/serving")
    @RequirePerm("feature:edit")
    public T4FeatureService.FeatureView updateServing(@PathVariable String code,
                                                      @RequestBody T4FeatureService.ServingCmd cmd) {
        return t4FeatureService.updateServing(code, cmd, DataScope.currentActor());
    }
}
