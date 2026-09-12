package com.meiyun.ai.provider;

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

/**
 * AI 供应商接入（模型接入页 /ai/providers）。
 * API Key 仅写入（掩码回显），不提供任何明文读取端点。
 */
@RestController
@RequestMapping("/api/ai/providers")
@RequirePerm("aiAdmin:view")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public List<ProviderService.ProviderView> list() {
        return providerService.list();
    }

    @GetMapping("/{id}")
    public ProviderService.ProviderView get(@PathVariable Long id) {
        return providerService.get(id);
    }

    @PostMapping
    @RequirePerm("aiAdmin:edit")
    public ProviderService.SaveResult create(@RequestBody ProviderService.ProviderCmd cmd) {
        return providerService.create(cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}")
    @RequirePerm("aiAdmin:edit")
    public ProviderService.SaveResult update(@PathVariable Long id,
                                             @RequestBody ProviderService.ProviderCmd cmd) {
        return providerService.update(id, cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}/delete")
    @RequirePerm("aiAdmin:edit")
    public Map<String, Object> delete(@PathVariable Long id) {
        ProviderService.SaveResult r = providerService.delete(id, DataScope.currentActor());
        return Map.of("changed", r.changed());
    }
}
