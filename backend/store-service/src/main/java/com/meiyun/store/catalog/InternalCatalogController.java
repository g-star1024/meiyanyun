package com.meiyun.store.catalog;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 卡项目录内部只读端点（B16，服务间调用专用）：txn 售卡下单前取在售模板、定售价与次数/有效期。
 *
 * <p>红线边界：模板定价/上下架是 store 域职责，不对业务页面另行开放写口——仅以系统身份
 * （X-Internal-Token，perms=["*"]）放行，普通登录人无 {@code internal:catalog-read}
 * 权限 → 403。txn 域不直读 catalog_product 表，由 store 域按自身语义校验「门店可见 + 在售」；
 * 取价失败（模板不存在/本店不可售 404、已下架 409）则 txn 售卡下单回滚（4xx 中文透传 / 5xx 502）。
 * 出参金额单位为分（priceFen），与订单金额口径一致。
 */
@RestController
@RequestMapping("/api/stores/internal/catalog")
public class InternalCatalogController {

    private final CatalogService service;

    public InternalCatalogController(CatalogService service) {
        this.service = service;
    }

    /**
     * 取在售模板：GET /api/stores/internal/catalog/{productCode}?storeCode=SST01。
     * 集团通用模板（store_code 空串）全店可售；本店模板仅本店可售；已下架拒绝售卡。
     */
    @GetMapping("/{productCode}")
    @RequirePerm("internal:catalog-read")
    public Map<String, Object> forSale(@PathVariable String productCode,
                                       @RequestParam(name = "storeCode", required = false) String storeCode) {
        return service.getForSale(productCode, storeCode);
    }
}
