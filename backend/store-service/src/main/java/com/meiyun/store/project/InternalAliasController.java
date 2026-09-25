package com.meiyun.store.project;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目别名内部只读端点（P5-B96 卡1，服务间调用专用）：txn 域 StoreProjectClient 全量拉取
 * ACTIVE 别名构建两级命中缓存（精确名 → 门店级别名 → 全局别名），R02 品类/集团大屏/毛利共用。
 *
 * <p>红线边界：别名建档/启停/删除语义归 store 域运营 CRUD，此端点仅以系统身份（X-Internal-Token，
 * perms=["*"]）放行 {@code internal:catalog-read}，普通登录人无该权限 → 403；txn/finance 域不直读
 * project_alias 表。固定只返回 ACTIVE 别名的轻量行（alias/storeCode/sku/skuName/serviceCategory
 * 五列，serviceCategory 由 SKU join 冗余带出，sku 被删时为 null 如实带出）。
 */
@RestController
@RequestMapping("/api/stores/internal/project-aliases")
public class InternalAliasController {

    private final ProjectAliasService aliasService;

    public InternalAliasController(ProjectAliasService aliasService) {
        this.aliasService = aliasService;
    }

    /** ACTIVE 别名全量：GET /api/stores/internal/project-aliases。 */
    @GetMapping
    @RequirePerm("internal:catalog-read")
    public List<Map<String, Object>> activeAliases() {
        return aliasService.activeAliasRows();
    }
}
