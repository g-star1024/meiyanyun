package com.meiyun.store.catalog;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 卡项/疗程目录公开端点（B15，网关 /api/stores 路由进 store-service，路径不重写）。
 *
 * <p>查询走 {@code catalog:view}；新建、编辑、上下架走 {@code catalog:edit}。
 * 数据域：store_code 空串 = 集团通用模板（全门店可见可售）。
 * SUPER/GROUP/BRAND 读可不传 storeCode（查全量），写不传 = 集团模板；传店即写该店。
 * STORE/SELF 角色读写强制本店，且不可写集团模板（mustFindInStore 对集团行 404）。
 */
@RestController
@RequestMapping("/api/stores")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    /** 目录列表（集团模板 + 本店模板；金额元；includes 数组化）。 */
    @GetMapping("/catalog")
    @RequirePerm("catalog:view")
    public List<Map<String, Object>> listCatalog(@RequestParam(value = "storeCode", required = false) String storeCode,
                                                 @RequestParam(value = "type", required = false) String type,
                                                 @RequestParam(value = "status", required = false) String status,
                                                 @RequestParam(value = "keyword", required = false) String keyword) {
        return service.listCatalog(resolveStoreCode(storeCode), type, status, keyword);
    }

    /** 新建模板（编码后端生成；高权限缺省门店 = 集团通用模板）。 */
    @PostMapping("/catalog")
    @RequirePerm("catalog:edit")
    public Map<String, Object> createCatalog(@RequestBody CreateCatalogCmd cmd) {
        if (cmd == null) throw CatalogService.badReq("请求体不能为空");
        CatalogProduct p = service.createCatalog(resolveWriteStoreCode(cmd.storeCode()), cmd.productType(),
                cmd.name(), cmd.category(), cmd.sessions(), cmd.validityDays(), cmd.priceFen(),
                cmd.originalPriceFen(), cmd.transferable(), cmd.status(), cmd.includes(),
                cmd.description(), operator());
        return Map.of("id", p.getId(), "storeCode", p.getStoreCode(), "productCode", p.getProductCode());
    }

    /** 编辑模板（不可改类型/编码/门店；价格直接生效）。 */
    @PostMapping("/catalog/{id}")
    @RequirePerm("catalog:edit")
    public Map<String, Object> updateCatalog(@PathVariable("id") Long id, @RequestBody UpdateCatalogCmd cmd) {
        if (cmd == null) throw CatalogService.badReq("请求体不能为空");
        service.updateCatalog(resolveWriteStoreCode(cmd.storeCode()), id, cmd.name(), cmd.category(),
                cmd.sessions(), cmd.validityDays(), cmd.priceFen(), cmd.originalPriceFen(),
                cmd.transferable(), cmd.includes(), cmd.description(), operator());
        return Map.of("ok", true);
    }

    /** 上架/下架切换（ON_SHELF ↔ OFF_SHELF）。 */
    @PostMapping("/catalog/{id}/toggle")
    @RequirePerm("catalog:edit")
    public Map<String, Object> toggle(@PathVariable("id") Long id, @RequestBody StoreScopeCmd cmd) {
        String storeCode = cmd == null ? null : cmd.storeCode();
        service.toggle(resolveWriteStoreCode(storeCode), id, operator());
        return Map.of("ok", true);
    }

    // ---- 数据域 ----

    /** 查询：门店/自助角色强制本店（集团模板由 Service 合并可见）；超管/集团/品牌可传 storeCode，缺省查全量。 */
    private String resolveStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return requested;
        }
        if (requested != null && !requested.isBlank()) {
            if (!DataScope.canReadStore(requested.trim())) {
                return "__NONE__";
            }
            return requested.trim();
        }
        return u.storeCode();
    }

    /** 写操作：超管/集团/品牌不传 storeCode 即写集团通用模板（空串），传店即写该店；门店角色仅能写本店。 */
    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return requested == null ? "" : requested.trim();
        }
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            throw CatalogService.badReq("当前账号无所属门店，无法维护门店目录");
        }
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode())) {
            throw CatalogService.badReq("门店角色仅能维护本店目录");
        }
        return u.storeCode();
    }

    private static String operator() {
        LoginUser u = SecurityContext.get();
        return u == null ? "系统" : u.staffName();
    }

    /** 新建入参；金额单位「分」，productCode 后端生成。 */
    public record CreateCatalogCmd(String storeCode, String productType, String name, String category,
                                   Integer sessions, Integer validityDays, Long priceFen, Long originalPriceFen,
                                   Boolean transferable, String status, List<String> includes,
                                   String description) {}

    /** 编辑入参（类型/编码不可改）；金额单位「分」。 */
    public record UpdateCatalogCmd(String storeCode, String name, String category, Integer sessions,
                                   Integer validityDays, Long priceFen, Long originalPriceFen,
                                   Boolean transferable, List<String> includes, String description) {}

    /** 仅携带门店数据域的入参（toggle）。 */
    public record StoreScopeCmd(String storeCode) {}
}
