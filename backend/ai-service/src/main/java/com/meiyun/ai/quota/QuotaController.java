package com.meiyun.ai.quota;

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

@RestController
@RequestMapping("/api/ai/quotas")
@RequirePerm("aiAdmin:view")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping
    public List<QuotaService.QuotaView> list() {
        return quotaService.list();
    }

    @PostMapping("/{scope}/save")
    @RequirePerm("aiAdmin:edit")
    public QuotaService.SaveResult save(@PathVariable String scope,
                                        @RequestParam String target,
                                        @RequestBody QuotaService.QuotaCmd cmd) {
        return quotaService.save(scope, target, cmd, DataScope.currentActor());
    }
}
