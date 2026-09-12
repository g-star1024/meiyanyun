package com.meiyun.ai.feature;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/features")
@RequirePerm("aiAdmin:view")
public class FeatureController {

    private final FeatureService featureService;

    public FeatureController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @GetMapping
    public List<FeatureService.BindingView> list() {
        return featureService.list();
    }

    @GetMapping("/{featureCode}")
    public FeatureService.BindingView get(@PathVariable String featureCode) {
        return featureService.get(featureCode);
    }

    @PostMapping("/{featureCode}/binding")
    @RequirePerm("aiAdmin:edit")
    public FeatureService.SaveResult bind(@PathVariable String featureCode,
                                          @RequestBody FeatureService.BindingCmd cmd) {
        return featureService.saveBinding(featureCode, cmd, DataScope.currentActor());
    }

    @PostMapping("/{featureCode}/roles")
    @RequirePerm("aiAdmin:edit")
    public FeatureService.SaveResult roles(@PathVariable String featureCode,
                                           @RequestBody Map<String, Boolean> roles) {
        return featureService.saveRoles(featureCode, roles, DataScope.currentActor());
    }
}
