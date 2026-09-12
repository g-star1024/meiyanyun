package com.meiyun.ai.security;

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

import java.util.List;

@RestController
@RequestMapping("/api/ai/sensitive")
@RequirePerm("aiAdmin:view")
public class SensitiveWordController {

    private final SensitiveWordAdminService sensitiveWordAdminService;

    public SensitiveWordController(SensitiveWordAdminService sensitiveWordAdminService) {
        this.sensitiveWordAdminService = sensitiveWordAdminService;
    }

    @GetMapping("/words")
    public List<SensitiveWordAdminService.WordView> words() {
        return sensitiveWordAdminService.words();
    }

    @PostMapping("/words")
    @RequirePerm("aiAdmin:edit")
    public SensitiveWordAdminService.SaveResult create(@RequestBody SensitiveWordAdminService.WordCmd cmd) {
        return sensitiveWordAdminService.create(cmd, DataScope.currentActor());
    }

    @PostMapping("/words/{id}")
    @RequirePerm("aiAdmin:edit")
    public SensitiveWordAdminService.SaveResult update(@PathVariable Long id,
                                                       @RequestBody SensitiveWordAdminService.WordCmd cmd) {
        return sensitiveWordAdminService.update(id, cmd, DataScope.currentActor());
    }

    @GetMapping("/hits")
    public Page<SensitiveWordAdminService.HitView> hits(@RequestParam(required = false) String category,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return sensitiveWordAdminService.hits(category, page, size);
    }

    @GetMapping("/hits/stats")
    public SensitiveWordAdminService.HitStats stats() {
        return sensitiveWordAdminService.stats();
    }

    @PostMapping("/hits/{id}/mark-fp")
    @RequirePerm("aiAdmin:edit")
    public SensitiveWordAdminService.SaveResult markFalsePositive(@PathVariable Long id) {
        return sensitiveWordAdminService.markFalsePositive(id, DataScope.currentActor());
    }
}
