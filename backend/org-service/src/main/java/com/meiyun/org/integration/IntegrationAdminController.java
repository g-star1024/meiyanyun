package com.meiyun.org.integration;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 外部依赖配置窗口管理面（真人，/api/org 前缀，网关正常鉴权）：
 * 列表（integration:view）、upsert 与连接测试（integration:edit）。
 * P0 目录固定 7 项，不开放自由建项，故不新增真人权限码（integration:create 留待 P1/P2 扩项）。
 */
@RestController
@RequestMapping("/api/org/integrations")
public class IntegrationAdminController {

    private final IntegrationService service;

    public IntegrationAdminController(IntegrationService service) {
        this.service = service;
    }

    /** 目录全量视图：code/category/name/valueKind/baseUrl/hasSecret/secretMask/boolValue/enabled/remark/lastTest*。 */
    @GetMapping
    @RequirePerm("integration:view")
    public List<IntegrationService.IntegrationView> list() {
        return service.listViews();
    }

    /**
     * upsert 目录行：URL 类保存地址（非 https 须 insecureHttpConfirmed 二次确认）；
     * SECRET 留空/含掩码不改原密钥；SWITCH 只认 boolValue+enabled。
     */
    @PostMapping("/{code}")
    @RequirePerm("integration:edit")
    public IntegrationService.IntegrationView upsert(@PathVariable String code,
                                                     @RequestBody IntegrationService.UpsertRequest req) {
        try {
            return service.upsert(code, req, DataScope.currentActor());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 测试连接：URL 探测 / SECRET 格式校验（不做假握手）/ SWITCH 返回当前状态。 */
    @PostMapping("/{code}/test")
    @RequirePerm("integration:edit")
    public IntegrationService.TestResult test(@PathVariable String code) {
        try {
            return service.test(code, DataScope.currentActor());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
