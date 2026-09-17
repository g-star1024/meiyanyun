package com.meiyun.org.integration;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 外部依赖配置服务间分发（系统身份，网关 Go isInternalPath 对外裸 404，仅 compose 内网服务名直连）：
 * GET /api/org/internal/integrations/snapshot 返回全量 7 行快照（含禁用项与明文 secret）。
 *
 * <p>internal:integration-read 为新内部权限码，不注册给任何真人角色；明文 secret 仅此端点下发。
 */
@RestController
@RequestMapping("/api/org/internal/integrations")
public class InternalIntegrationController {

    private final IntegrationService service;

    public InternalIntegrationController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    @RequirePerm("internal:integration-read")
    public List<IntegrationService.SnapshotItem> snapshot() {
        return service.snapshot();
    }
}
