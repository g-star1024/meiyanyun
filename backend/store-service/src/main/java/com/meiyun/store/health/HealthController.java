package com.meiyun.store.health;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
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
@RequestMapping("/api/stores/health")
public class HealthController {

    private final HealthService svc;

    public HealthController(HealthService svc) {
        this.svc = svc;
    }

    @GetMapping("/checks")
    @RequirePerm("health:view")
    public List<Map<String, Object>> checks() {
        return svc.listChecks();
    }

    @GetMapping("/issues")
    @RequirePerm("health:view")
    public List<Map<String, Object>> issues(@RequestParam(required = false) String storeCode,
                                            @RequestParam(required = false) String status) {
        return svc.listIssues(storeCode, status);
    }

    @PostMapping("/issues/{id}/start")
    @RequirePerm("health:edit")
    public Map<String, String> start(@PathVariable String id) {
        svc.startIssue(id, actor());
        return Map.of("status", "ok");
    }

    @PostMapping("/issues/{id}/resolve")
    @RequirePerm("health:edit")
    public Map<String, String> resolve(@PathVariable String id, @RequestBody ResolveCmd cmd) {
        svc.resolveIssue(id, cmd.resolution(), actor());
        return Map.of("status", "ok");
    }

    @PostMapping("/issues/{id}/ignore")
    @RequirePerm("health:edit")
    public Map<String, String> ignore(@PathVariable String id) {
        svc.ignoreIssue(id, actor());
        return Map.of("status", "ok");
    }

    @PostMapping("/checks/{storeCode}/rerun")
    @RequirePerm("health:edit")
    public Map<String, Object> rerun(@PathVariable String storeCode) {
        return svc.rerun(storeCode, actor());
    }

    private String actor() {
        var u = SecurityContext.get();
        return u == null ? "system" : u.staffName();
    }

    public record ResolveCmd(String resolution) {
    }
}
