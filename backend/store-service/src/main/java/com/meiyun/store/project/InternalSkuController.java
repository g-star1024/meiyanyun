package com.meiyun.store.project;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SKU 目录内部只读端点（F3 卡1，服务间调用专用）：txn 域 StoreProjectClient 全量拉取
 * SKU 行构建「名称→serviceCategory」「SKU→durationMin」映射缓存（M1 大屏品类占比 /
 * 预约 sku_code 外键校验 / 派单时长真源）。
 *
 * <p>原链路带 X-Internal-Token 调业务端点 {@code GET /api/stores/skus}（brand:view）；
 * F3 卡1 方案 A 收口后 token 对非 internal 路径不再授予身份，故全量目录读取迁伞下，
 * 权限点 {@code internal:catalog-read}（与 InternalAliasController 同），员工 JWT 一律 403。
 * 行形状与 {@link ProjectService#listSkus} 空参全量完全一致，调用方解析逻辑零改动。
 */
@RestController
@RequestMapping("/api/stores/internal/skus")
public class InternalSkuController {

    private final ProjectService projectService;

    public InternalSkuController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** SKU 全量（含各状态，调用方自行按 status 过滤）：GET /api/stores/internal/skus。 */
    @GetMapping
    @RequirePerm("internal:catalog-read")
    public List<Map<String, Object>> allSkus() {
        return projectService.listSkus(null, null, null, null);
    }
}
