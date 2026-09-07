package com.meiyun.store.project;

import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目目录域服务（B14）：品牌 → 品类（二级树）→ 项目 SKU 三级集团主数据。
 *
 * <p>三级数据均为集团级（无 store_code），不做门店数据域裁决；读走 {@code brand:view}、写走 {@code brand:edit}。
 * 金额 Long「分」落库、读接口出参元（Yuan）；store_types/risk_tags 逗号串落库、出参数组化。
 * 删除规则：品类名下（含子品类）仍有 SKU 时 422 拒绝，通过后连带删子品类（DESIGN-P5 §3.2）。
 * SKU 更新不覆盖 service_category/risk_tags（两列由种子播种，表单一期无录入项，DESIGN-P5 §二补充裁决）。
 */
@Service
public class ProjectService {

    private final ProductBrandRepository brandRepo;
    private final ProductCategoryRepository categoryRepo;
    private final ProductSkuRepository skuRepo;
    private final ConsumableAuditRecorder audit;

    public ProjectService(ProductBrandRepository brandRepo,
                          ProductCategoryRepository categoryRepo,
                          ProductSkuRepository skuRepo,
                          ConsumableAuditRecorder audit) {
        this.brandRepo = brandRepo;
        this.categoryRepo = categoryRepo;
        this.skuRepo = skuRepo;
        this.audit = audit;
    }

    /** 400 参数错误（中文） */
    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ---- 品牌 ----

    /** 品牌列表（含 stats：品类数/项目数/启用项目数/平均挂牌价元）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listBrands() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProductBrand b : brandRepo.findAllByOrderByBrandCode()) {
            Map<String, Object> row = brandRow(b);
            List<ProductSku> skus = skuRepo.findByBrandIdOrderBySku(b.getId());
            long active = skus.stream().filter(s -> "ACTIVE".equals(s.getStatus())).count();
            long avgFen = skus.isEmpty() ? 0L
                    : Math.round(skus.stream().mapToLong(s -> s.getListPriceFen() == null ? 0L : s.getListPriceFen())
                    .average().orElse(0));
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("categoryCount", categoryRepo.countByBrandId(b.getId()));
            stats.put("productCount", (long) skus.size());
            stats.put("activeProductCount", active);
            stats.put("avgListPriceYuan", fenToYuan(avgFen));
            row.put("stats", stats);
            out.add(row);
        }
        return out;
    }

    /** 新建品牌：brand_code 全局唯一，重复 409。 */
    @Transactional
    public ProductBrand createBrand(String code, String name, String shortName, String origin,
                                    String supplier, String logoColor, String remark, String operator) {
        if (isBlank(code) || isBlank(name)) {
            throw badReq("品牌编码、品牌名称均不能为空");
        }
        if (brandRepo.findByBrandCode(code.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "品牌编码「" + code + "」已存在，请勿重复建档");
        }
        ProductBrand b = new ProductBrand();
        b.setBrandCode(code.trim());
        b.setName(name.trim());
        b.setShortName(trimToNull(shortName));
        b.setOrigin(trimToNull(origin));
        b.setSupplier(trimToNull(supplier));
        b.setLogoColor(trimToNull(logoColor));
        b.setRemark(trimToNull(remark));
        b.setStatus("ACTIVE");
        b.setCreatedBy(operator);
        b.setUpdatedBy(operator);
        brandRepo.save(b);
        audit.record("BRAND", "BRAND-" + b.getId(), operator, "新建品牌",
                String.format("{\"brandCode\":%s,\"name\":%s,\"supplier\":%s}",
                        jsonStr(b.getBrandCode()), jsonStr(b.getName()), jsonStr(b.getSupplier())));
        return b;
    }

    /** 更新品牌（同名字段，null 不覆盖）；不存在 404。 */
    @Transactional
    public void updateBrand(Long id, String name, String shortName, String origin,
                            String supplier, String remark, String operator) {
        ProductBrand b = mustFindBrand(id);
        if (name != null && !name.isBlank()) b.setName(name.trim());
        if (shortName != null) b.setShortName(trimToNull(shortName));
        if (origin != null) b.setOrigin(trimToNull(origin));
        if (supplier != null) b.setSupplier(trimToNull(supplier));
        if (remark != null) b.setRemark(trimToNull(remark));
        b.setUpdatedBy(operator);
        b.setUpdatedAt(OffsetDateTime.now());
        brandRepo.save(b);
        audit.record("BRAND", "BRAND-" + b.getId(), operator, "更新品牌",
                String.format("{\"brandCode\":%s,\"name\":%s}",
                        jsonStr(b.getBrandCode()), jsonStr(b.getName())));
    }

    /** 品牌启停：ACTIVE/INACTIVE；已是目标态幂等返回不审计。 */
    @Transactional
    public void setBrandStatus(Long id, String status, String operator) {
        String st = normalizeStatus(status);
        ProductBrand b = mustFindBrand(id);
        if (st.equals(b.getStatus())) return;
        b.setStatus(st);
        b.setUpdatedBy(operator);
        b.setUpdatedAt(OffsetDateTime.now());
        brandRepo.save(b);
        audit.record("BRAND", "BRAND-" + b.getId(), operator, "品牌启停",
                String.format("{\"brandCode\":%s,\"status\":%s}",
                        jsonStr(b.getBrandCode()), jsonStr(st)));
    }

    // ---- 品类 ----

    /** 品类列表（扁平返回，树形由前端按 parentId 组）；brandId 可选过滤。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCategories(Long brandId) {
        List<ProductCategory> all = brandId == null
                ? categoryRepo.findAllByOrderByCategoryCode()
                : categoryRepo.findByBrandIdOrderBySortAscIdAsc(brandId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProductCategory c : all) {
            out.add(categoryRow(c));
        }
        return out;
    }

    /** 新建品类：code 唯一 409；brandId 不存在 400；parentId 非空时校验同父品牌；sort 缺省同品牌 max+1。 */
    @Transactional
    public ProductCategory createCategory(String code, String name, Long brandId, Long parentId,
                                          Integer sort, String remark, String operator) {
        if (isBlank(code) || isBlank(name) || brandId == null) {
            throw badReq("品类编码、品类名称、所属品牌均不能为空");
        }
        mustFindBrand(brandId);
        if (categoryRepo.findByCategoryCode(code.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "品类编码「" + code + "」已存在，请勿重复建档");
        }
        if (parentId != null) {
            ProductCategory parent = categoryRepo.findById(parentId)
                    .orElseThrow(() -> badReq("父品类不存在：" + parentId));
            if (!parent.getBrandId().equals(brandId)) {
                throw badReq("父品类与所选品牌不一致");
            }
        }
        int order = sort != null ? sort : nextCategorySort(brandId);
        ProductCategory c = new ProductCategory();
        c.setCategoryCode(code.trim());
        c.setName(name.trim());
        c.setBrandId(brandId);
        c.setParentId(parentId);
        c.setSort(order);
        c.setRemark(trimToNull(remark));
        c.setStatus("ACTIVE");
        c.setCreatedBy(operator);
        c.setUpdatedBy(operator);
        categoryRepo.save(c);
        audit.record("BRAND", "CATEGORY-" + c.getId(), operator, "新建品类",
                String.format("{\"categoryCode\":%s,\"name\":%s,\"brandId\":%d,\"parentId\":%s}",
                        jsonStr(c.getCategoryCode()), jsonStr(c.getName()), brandId,
                        parentId == null ? "null" : parentId.toString()));
        return c;
    }

    /** 更新品类（null 不覆盖）；不存在 404。 */
    @Transactional
    public void updateCategory(Long id, String name, Long parentId, Integer sort,
                               String remark, String operator) {
        ProductCategory c = mustFindCategory(id);
        if (name != null && !name.isBlank()) c.setName(name.trim());
        if (parentId != null) {
            if (parentId.equals(id)) throw badReq("父品类不能选择自身");
            ProductCategory parent = categoryRepo.findById(parentId)
                    .orElseThrow(() -> badReq("父品类不存在：" + parentId));
            if (!parent.getBrandId().equals(c.getBrandId())) {
                throw badReq("父品类与所属品牌不一致");
            }
            c.setParentId(parentId);
        }
        if (sort != null) c.setSort(sort);
        if (remark != null) c.setRemark(trimToNull(remark));
        c.setUpdatedBy(operator);
        c.setUpdatedAt(OffsetDateTime.now());
        categoryRepo.save(c);
        audit.record("BRAND", "CATEGORY-" + c.getId(), operator, "更新品类",
                String.format("{\"categoryCode\":%s,\"name\":%s}",
                        jsonStr(c.getCategoryCode()), jsonStr(c.getName())));
    }

    /** 品类启停；幂等不审计。 */
    @Transactional
    public void setCategoryStatus(Long id, String status, String operator) {
        String st = normalizeStatus(status);
        ProductCategory c = mustFindCategory(id);
        if (st.equals(c.getStatus())) return;
        c.setStatus(st);
        c.setUpdatedBy(operator);
        c.setUpdatedAt(OffsetDateTime.now());
        categoryRepo.save(c);
        audit.record("BRAND", "CATEGORY-" + c.getId(), operator, "品类启停",
                String.format("{\"categoryCode\":%s,\"status\":%s}",
                        jsonStr(c.getCategoryCode()), jsonStr(st)));
    }

    /**
     * 删除品类：本品类或其子品类名下仍有 SKU → 422「该品类（含子品类）下仍有项目，无法删除」；
     * 通过校验后连带删除子品类。
     */
    @Transactional
    public void deleteCategory(Long id, String operator) {
        ProductCategory c = mustFindCategory(id);
        if (!skuRepo.findByCategoryId(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "该品类（含子品类）下仍有项目，无法删除");
        }
        List<ProductCategory> children = categoryRepo.findByParentId(id);
        for (ProductCategory child : children) {
            if (!skuRepo.findByCategoryId(child.getId()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "该品类（含子品类）下仍有项目，无法删除");
            }
        }
        for (ProductCategory child : children) {
            categoryRepo.delete(child);
        }
        categoryRepo.delete(c);
        audit.record("BRAND", "CATEGORY-" + id, operator, "删除品类",
                String.format("{\"categoryCode\":%s,\"name\":%s,\"deletedChildren\":%d}",
                        jsonStr(c.getCategoryCode()), jsonStr(c.getName()), children.size()));
    }

    // ---- SKU ----

    /** SKU 列表（brandId/categoryId/status/keyword 可选过滤；出参金额元、storeTypes/riskTags 数组）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSkus(Long brandId, Long categoryId, String keyword, String status) {
        String st = isBlank(status) || "ALL".equals(status) ? null : status.trim();
        String kw = isBlank(keyword) ? null : keyword.trim().toLowerCase();
        List<ProductSku> skus = skuRepo.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (brandId != null) {
                ps.add(cb.equal(root.get("brandId"), brandId));
            }
            if (categoryId != null) {
                ps.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (st != null) {
                ps.add(cb.equal(root.get("status"), st));
            }
            if (kw != null) {
                String like = "%" + kw + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("sku")), like),
                        cb.like(cb.lower(root.get("name")), like)));
            }
            query.orderBy(cb.asc(root.get("sku")));
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProductSku s : skus) {
            out.add(skuRow(s));
        }
        return out;
    }

    /** 新建 SKU：sku 全局唯一 409；品牌/品类不存在 400；金额入参分。 */
    @Transactional
    public ProductSku createSku(String sku, String name, Long brandId, Long categoryId, String unit,
                                Long listPriceFen, Long costPriceFen, List<String> storeTypes,
                                Integer durationMin, String serviceCategory, String remark, String operator) {
        if (isBlank(sku) || isBlank(name) || brandId == null || categoryId == null || isBlank(unit)) {
            throw badReq("SKU 编码、项目名称、所属品牌、所属品类、单位均不能为空");
        }
        mustFindBrand(brandId);
        ProductCategory category = categoryRepo.findById(categoryId)
                .orElseThrow(() -> badReq("所属品类不存在：" + categoryId));
        if (!category.getBrandId().equals(brandId)) {
            throw badReq("所属品类与品牌不一致");
        }
        if (skuRepo.findBySku(sku.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "项目编码「" + sku + "」已存在，请勿重复建档");
        }
        ProductSku s = new ProductSku();
        s.setSku(sku.trim());
        s.setName(name.trim());
        s.setBrandId(brandId);
        s.setCategoryId(categoryId);
        s.setUnit(unit.trim());
        s.setListPriceFen(nonNegativeFen(listPriceFen));
        s.setCostPriceFen(nonNegativeFen(costPriceFen));
        s.setStoreTypes(joinList(storeTypes));
        s.setDurationMin(durationMin == null ? 0 : Math.max(0, durationMin));
        s.setServiceCategory(trimToNull(serviceCategory));
        s.setRemark(trimToNull(remark));
        s.setStatus("ACTIVE");
        s.setCreatedBy(operator);
        s.setUpdatedBy(operator);
        skuRepo.save(s);
        audit.record("BRAND", "SKU-" + s.getId(), operator, "新建项目SKU",
                String.format("{\"sku\":%s,\"name\":%s,\"brandId\":%d,\"categoryId\":%d,\"listPriceFen\":%d}",
                        jsonStr(s.getSku()), jsonStr(s.getName()), brandId, categoryId, s.getListPriceFen()));
        return s;
    }

    /**
     * 更新 SKU（null 不覆盖）；不覆盖 service_category/risk_tags（DESIGN-P5 §二补充裁决）。
     */
    @Transactional
    public void updateSku(Long id, String name, Long categoryId, String unit, Long listPriceFen,
                          Long costPriceFen, List<String> storeTypes, Integer durationMin,
                          String remark, String operator) {
        ProductSku s = mustFindSku(id);
        if (name != null && !name.isBlank()) s.setName(name.trim());
        if (categoryId != null) {
            ProductCategory category = categoryRepo.findById(categoryId)
                    .orElseThrow(() -> badReq("所属品类不存在：" + categoryId));
            if (!category.getBrandId().equals(s.getBrandId())) {
                throw badReq("所属品类与品牌不一致");
            }
            s.setCategoryId(categoryId);
        }
        if (unit != null && !unit.isBlank()) s.setUnit(unit.trim());
        if (listPriceFen != null) s.setListPriceFen(nonNegativeFen(listPriceFen));
        if (costPriceFen != null) s.setCostPriceFen(nonNegativeFen(costPriceFen));
        if (storeTypes != null) s.setStoreTypes(joinList(storeTypes));
        if (durationMin != null) s.setDurationMin(Math.max(0, durationMin));
        if (remark != null) s.setRemark(trimToNull(remark));
        s.setUpdatedBy(operator);
        s.setUpdatedAt(OffsetDateTime.now());
        skuRepo.save(s);
        audit.record("BRAND", "SKU-" + s.getId(), operator, "更新项目SKU",
                String.format("{\"sku\":%s,\"name\":%s,\"listPriceFen\":%d}",
                        jsonStr(s.getSku()), jsonStr(s.getName()), s.getListPriceFen()));
    }

    /** SKU 启停；幂等不审计。 */
    @Transactional
    public void setSkuStatus(Long id, String status, String operator) {
        String st = normalizeStatus(status);
        ProductSku s = mustFindSku(id);
        if (st.equals(s.getStatus())) return;
        s.setStatus(st);
        s.setUpdatedBy(operator);
        s.setUpdatedAt(OffsetDateTime.now());
        skuRepo.save(s);
        audit.record("BRAND", "SKU-" + s.getId(), operator, "项目SKU启停",
                String.format("{\"sku\":%s,\"status\":%s}", jsonStr(s.getSku()), jsonStr(st)));
    }

    // ---- 种子（供 DataInitializer 复用，不写审计） ----

    @Transactional
    public ProductBrand seedBrand(String code, String name, String shortName, String origin,
                                  String supplier, String status, String logoColor, String remark,
                                  String operator) {
        ProductBrand b = new ProductBrand();
        b.setBrandCode(code);
        b.setName(name);
        b.setShortName(shortName);
        b.setOrigin(origin);
        b.setSupplier(supplier);
        b.setStatus(status == null ? "ACTIVE" : status);
        b.setLogoColor(logoColor);
        b.setRemark(remark);
        b.setCreatedBy(operator);
        b.setUpdatedBy(operator);
        return brandRepo.save(b);
    }

    @Transactional
    public ProductCategory seedCategory(String code, String name, Long brandId, Long parentId,
                                        int sort, String remark, String operator) {
        ProductCategory c = new ProductCategory();
        c.setCategoryCode(code);
        c.setName(name);
        c.setBrandId(brandId);
        c.setParentId(parentId);
        c.setSort(sort);
        c.setRemark(remark);
        c.setStatus("ACTIVE");
        c.setCreatedBy(operator);
        c.setUpdatedBy(operator);
        return categoryRepo.save(c);
    }

    @Transactional
    public ProductSku seedSku(String sku, String name, Long brandId, Long categoryId, String unit,
                              long listPriceFen, long costPriceFen, String status, String storeTypes,
                              int durationMin, String serviceCategory, String riskTags,
                              String remark, String operator) {
        ProductSku s = new ProductSku();
        s.setSku(sku);
        s.setName(name);
        s.setBrandId(brandId);
        s.setCategoryId(categoryId);
        s.setUnit(unit);
        s.setListPriceFen(listPriceFen);
        s.setCostPriceFen(costPriceFen);
        s.setStatus(status == null ? "ACTIVE" : status);
        s.setStoreTypes(storeTypes == null ? "" : storeTypes);
        s.setDurationMin(durationMin);
        s.setServiceCategory(serviceCategory);
        s.setRiskTags(riskTags);
        s.setRemark(remark);
        s.setCreatedBy(operator);
        s.setUpdatedBy(operator);
        return skuRepo.save(s);
    }

    // ---- 出参装配 ----

    private Map<String, Object> brandRow(ProductBrand b) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", b.getId());
        row.put("code", b.getBrandCode());
        row.put("name", b.getName());
        row.put("shortName", b.getShortName());
        row.put("origin", b.getOrigin());
        row.put("supplier", b.getSupplier());
        row.put("status", b.getStatus());
        row.put("logoColor", b.getLogoColor());
        row.put("remark", b.getRemark());
        row.put("createdAt", b.getCreatedAt());
        return row;
    }

    private Map<String, Object> categoryRow(ProductCategory c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("code", c.getCategoryCode());
        row.put("name", c.getName());
        row.put("brandId", c.getBrandId());
        row.put("parentId", c.getParentId());
        row.put("status", c.getStatus());
        row.put("sort", c.getSort());
        row.put("remark", c.getRemark());
        row.put("createdAt", c.getCreatedAt());
        return row;
    }

    private Map<String, Object> skuRow(ProductSku s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("sku", s.getSku());
        row.put("name", s.getName());
        row.put("brandId", s.getBrandId());
        row.put("categoryId", s.getCategoryId());
        row.put("unit", s.getUnit());
        row.put("listPriceYuan", fenToYuan(s.getListPriceFen()));
        row.put("costPriceYuan", fenToYuan(s.getCostPriceFen()));
        row.put("status", s.getStatus());
        row.put("storeTypes", splitCsv(s.getStoreTypes()));
        row.put("durationMin", s.getDurationMin());
        row.put("serviceCategory", s.getServiceCategory());
        row.put("riskTags", splitCsv(s.getRiskTags()));
        row.put("remark", s.getRemark());
        row.put("createdAt", s.getCreatedAt());
        return row;
    }

    // ---- 内部辅助 ----

    private ProductBrand mustFindBrand(Long id) {
        return brandRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "品牌不存在：" + id));
    }

    private ProductCategory mustFindCategory(Long id) {
        return categoryRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "品类不存在：" + id));
    }

    private ProductSku mustFindSku(Long id) {
        return skuRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在：" + id));
    }

    private int nextCategorySort(Long brandId) {
        return categoryRepo.findByBrandIdOrderBySortAscIdAsc(brandId).stream()
                .mapToInt(ProductCategory::getSort).max().orElse(0) + 1;
    }

    private static String normalizeStatus(String status) {
        if (isBlank(status)) throw badReq("状态不能为空");
        String st = status.trim();
        if (!List.of("ACTIVE", "INACTIVE").contains(st)) {
            throw badReq("状态仅支持 ACTIVE/INACTIVE");
        }
        return st;
    }

    private static long nonNegativeFen(Long fen) {
        return fen == null ? 0L : Math.max(0L, fen);
    }

    private static double fenToYuan(Long fen) {
        return fen == null ? 0.0 : Math.round(fen) / 100.0;
    }

    private static String joinList(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return String.join(",", list.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** JSON 字符串转义（审计 payload 手工拼 JSON，与 RoomService.jsonStr 同口径） */
    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
