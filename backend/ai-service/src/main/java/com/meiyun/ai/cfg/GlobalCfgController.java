package com.meiyun.ai.cfg;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/cfg")
@RequirePerm("aiAdmin:view")
public class GlobalCfgController {

    private final GlobalCfgService cfgService;

    public GlobalCfgController(GlobalCfgService cfgService) {
        this.cfgService = cfgService;
    }

    @GetMapping
    public GlobalCfgService.CfgView get() {
        return cfgService.get();
    }

    @PostMapping
    @RequirePerm("aiAdmin:edit")
    public Map<String, Object> save(@RequestBody GlobalCfgService.CfgCmd cmd) {
        GlobalCfgService.SaveResult r = cfgService.save(cmd, DataScope.currentActor());
        return Map.of("changed", r.changed());
    }
}
