package com.meiyun.store.project;

import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目目录公开端点（B14，网关 /api/stores 路由进 store-service，路径不重写）。
 *
 * <p>品牌/品类/SKU 为集团级主数据，不做门店数据域裁决；查询走 {@code brand:view}，建档/更新/启停/删除走 {@code brand:edit}。
 * 金额写接口入参单位「分」（字段名带 Fen），读接口出参元（字段名带 Yuan）。
 */
@RestController
@RequestMapping("/api/stores")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    // ---- 品牌 ----

    @GetMapping("/brands")
    @RequirePerm("brand:view")
    public List<Map<String, Object>> listBrands() {
        return service.listBrands();
    }

    @PostMapping("/brands")
    @RequirePerm("brand:edit")
    public Map<String, Object> createBrand(@RequestBody BrandCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        ProductBrand b = service.createBrand(cmd.code(), cmd.name(), cmd.shortName(), cmd.origin(),
                cmd.supplier(), cmd.logoColor(), cmd.remark(), operator());
        return Map.of("id", b.getId(), "code", b.getBrandCode(), "name", b.getName());
    }

    @PostMapping("/brands/{id}")
    @RequirePerm("brand:edit")
    public Map<String, Object> updateBrand(@PathVariable("id") Long id, @RequestBody BrandUpdateCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        service.updateBrand(id, cmd.name(), cmd.shortName(), cmd.origin(), cmd.supplier(),
                cmd.remark(), operator());
        return Map.of("ok", true);
    }

    @PostMapping("/brands/{id}/status")
    @RequirePerm("brand:edit")
    public Map<String, Object> setBrandStatus(@PathVariable("id") Long id, @RequestBody StatusCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        service.setBrandStatus(id, cmd.status(), operator());
        return Map.of("ok", true);
    }

    // ---- 品类 ----

    @GetMapping("/categories")
    @RequirePerm("brand:view")
    public List<Map<String, Object>> listCategories(@RequestParam(value = "brandId", required = false) Long brandId) {
        return service.listCategories(brandId);
    }

    @PostMapping("/categories")
    @RequirePerm("brand:edit")
    public Map<String, Object> createCategory(@RequestBody CategoryCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        ProductCategory c = service.createCategory(cmd.code(), cmd.name(), cmd.brandId(), cmd.parentId(),
                cmd.sort(), cmd.remark(), operator());
        return Map.of("id", c.getId(), "code", c.getCategoryCode(), "name", c.getName());
    }

    @PostMapping("/categories/{id}")
    @RequirePerm("brand:edit")
    public Map<String, Object> updateCategory(@PathVariable("id") Long id, @RequestBody CategoryUpdateCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        service.updateCategory(id, cmd.name(), cmd.parentId(), cmd.sort(), cmd.remark(), operator());
        return Map.of("ok", true);
    }

    @PostMapping("/categories/{id}/status")
    @RequirePerm("brand:edit")
    public Map<String, Object> setCategoryStatus(@PathVariable("id") Long id, @RequestBody StatusCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        service.setCategoryStatus(id, cmd.status(), operator());
        return Map.of("ok", true);
    }

    @DeleteMapping("/categories/{id}")
    @RequirePerm("brand:edit")
    public Map<String, Object> deleteCategory(@PathVariable("id") Long id) {
        service.deleteCategory(id, operator());
        return Map.of("ok", true);
    }

    // ---- SKU ----

    @GetMapping("/skus")
    @RequirePerm("brand:view")
    public List<Map<String, Object>> listSkus(@RequestParam(value = "brandId", required = false) Long brandId,
                                              @RequestParam(value = "categoryId", required = false) Long categoryId,
                                              @RequestParam(value = "keyword", required = false) String keyword,
                                              @RequestParam(value = "status", required = false) String status) {
        return service.listSkus(brandId, categoryId, keyword, status);
    }

    @PostMapping("/skus")
    @RequirePerm("brand:edit")
    public Map<String, Object> createSku(@RequestBody SkuCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        ProductSku s = service.createSku(cmd.sku(), cmd.name(), cmd.brandId(), cmd.categoryId(), cmd.unit(),
                cmd.listPriceFen(), cmd.costPriceFen(), cmd.storeTypes(), cmd.durationMin(),
                cmd.serviceCategory(), cmd.remark(), operator());
        return Map.of("id", s.getId(), "sku", s.getSku(), "name", s.getName());
    }

    @PostMapping("/skus/{id}")
    @RequirePerm("brand:edit")
    public Map<String, Object> updateSku(@PathVariable("id") Long id, @RequestBody SkuUpdateCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        service.updateSku(id, cmd.name(), cmd.categoryId(), cmd.unit(), cmd.listPriceFen(),
                cmd.costPriceFen(), cmd.storeTypes(), cmd.durationMin(), cmd.remark(), operator());
        return Map.of("ok", true);
    }

    @PostMapping("/skus/{id}/status")
    @RequirePerm("brand:edit")
    public Map<String, Object> setSkuStatus(@PathVariable("id") Long id, @RequestBody StatusCmd cmd) {
        if (cmd == null) throw ProjectService.badReq("请求体不能为空");
        service.setSkuStatus(id, cmd.status(), operator());
        return Map.of("ok", true);
    }

    // ---- 内部 ----

    private static String operator() {
        LoginUser u = SecurityContext.get();
        return u == null ? "系统" : u.staffName();
    }

    /** 品牌新建入参；status 缺省由服务端置 ACTIVE。 */
    public record BrandCmd(String code, String name, String shortName, String origin, String supplier,
                           String logoColor, String remark, String status) {}

    /** 品牌更新入参（null 字段不覆盖）。 */
    public record BrandUpdateCmd(String name, String shortName, String origin, String supplier, String remark) {}

    /** 品类新建入参；sort 缺省取同品牌 max+1。 */
    public record CategoryCmd(String code, String name, Long brandId, Long parentId, Integer sort,
                              String remark) {}

    /** 品类更新入参（null 字段不覆盖）。 */
    public record CategoryUpdateCmd(String name, Long parentId, Integer sort, String remark) {}

    /** SKU 新建入参；金额 listPriceFen/costPriceFen 单位「分」，storeTypes 为数组。 */
    public record SkuCmd(String sku, String name, Long brandId, Long categoryId, String unit,
                         Long listPriceFen, Long costPriceFen, List<String> storeTypes, Integer durationMin,
                         String serviceCategory, String remark) {}

    /** SKU 更新入参（不覆盖 serviceCategory/riskTags；null 字段不覆盖）。 */
    public record SkuUpdateCmd(String name, Long categoryId, String unit, Long listPriceFen, Long costPriceFen,
                               List<String> storeTypes, Integer durationMin, String remark) {}

    /** 启停通用入参。 */
    public record StatusCmd(String status) {}
}
