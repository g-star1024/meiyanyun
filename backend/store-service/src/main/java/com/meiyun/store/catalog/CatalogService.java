package com.meiyun.store.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡项/疗程目录域服务（B15，DESIGN-P4 §4.5/§5.5）：集团/门店售卖模板的定义、上下架。
 *
 * <p>store_code 空串 = 集团通用模板（全门店可见可售）；列表对门店角色合并「集团模板 + 本店模板」。
 * 商品编码后端生成：卡项 CD-xxx / 疗程 CS-xxx，同类全局 max+1。金额 Long 分落库、出参元；
 * includes 以 JSON 数组字符串落库（一期自由文本，不强制 SKU 外键），出参数组化。
 * 本批不做售卡开卡（member_card 实例）与调价审批：价格编辑直接生效，状态仅 ON_SHELF/OFF_SHELF 两态。
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final CatalogProductRepository repo;
    private final ConsumableAuditRecorder audit;
    private final ObjectMapper objectMapper;

    public CatalogService(CatalogProductRepository repo,
                          ConsumableAuditRecorder audit,
                          ObjectMapper objectMapper) {
        this.repo = repo;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    /** 400 参数错误（中文） */
    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /**
     * 目录列表：storeCode 为 null（高权限未指定门店）查全量；否则合并集团通用模板（空串）与本店模板。
     * type/status/keyword 内存过滤（目录为小表）；keyword 匹配编码/名称。出参金额元、includes 数组化。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCatalog(String storeCode, String type, String status, String keyword) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        String tp = isBlank(type) || "ALL".equals(type) ? null : type.trim();
        String st = isBlank(status) || "ALL".equals(status) ? null : status.trim();
        String kw = isBlank(keyword) ? null : keyword.trim().toLowerCase();

        List<CatalogProduct> rows;
        if (sc == null) {
            rows = repo.findAllByOrderByProductCodeAsc();
        } else {
            rows = new ArrayList<>(repo.findByStoreCodeOrderByProductCodeAsc(""));
            if (!sc.isEmpty()) {
                rows.addAll(repo.findByStoreCodeOrderByProductCodeAsc(sc));
            }
            rows.sort(Comparator.comparing(CatalogProduct::getProductCode));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CatalogProduct p : rows) {
            if (tp != null && !tp.equals(p.getProductType())) continue;
            if (st != null && !st.equals(p.getStatus())) continue;
            if (kw != null
                    && !p.getProductCode().toLowerCase().contains(kw)
                    && !p.getName().toLowerCase().contains(kw)) {
                continue;
            }
            out.add(row(p));
        }
        return out;
    }

    /** 新建模板：编码后端生成（CD-/CS- 全局递增）；同店同名 409；高权限缺省门店 = 集团通用模板（空串）。 */
    @Transactional
    public CatalogProduct createCatalog(String storeCode, String productType, String name, String category,
                                        Integer sessions, Integer validityDays, Long priceFen, Long originalPriceFen,
                                        Boolean transferable, String status, List<String> includes,
                                        String description, String operator) {
        String sc = storeCode == null ? "" : storeCode.trim();
        String tp = normalizeType(productType);
        String nm = requireName(name);
        if (repo.existsByStoreCodeAndName(sc, nm)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    (sc.isEmpty() ? "集团目录" : "本店") + "已存在同名商品「" + nm + "」，请勿重复建档");
        }
        CatalogProduct p = new CatalogProduct();
        p.setStoreCode(sc);
        p.setProductCode(nextProductCode(tp));
        p.setName(nm);
        p.setProductType(tp);
        p.setCategory(trimToNull(category));
        p.setSessions(nonNegative(sessions, 1));
        p.setValidityDays(requirePositive(validityDays, "有效期天数"));
        p.setPriceFen(nonNegativeFen(priceFen));
        p.setOriginalPriceFen(nonNegativeFen(originalPriceFen));
        p.setTransferable(transferable != null && transferable);
        p.setStatus(normalizeStatus(status));
        p.setIncludes(toJson(includes));
        p.setDescription(trimToNull(description));
        p.setCreatedBy(operator);
        p.setUpdatedBy(operator);
        repo.save(p);
        audit.record("CATALOG", "CATALOG-" + p.getId(), operator,
                "新建" + typeLabel(tp) + "「" + nm + "」",
                String.format("{\"storeCode\":%s,\"productCode\":%s,\"name\":%s,\"productType\":%s,\"priceFen\":%d}",
                        jsonStr(sc), jsonStr(p.getProductCode()), jsonStr(nm), jsonStr(tp), p.getPriceFen()));
        return p;
    }

    /** 编辑模板：不可改类型/编码/门店；同店同名（排除自身）409；价格直接生效（本批无审批）。 */
    @Transactional
    public void updateCatalog(String storeCode, Long id, String name, String category, Integer sessions,
                              Integer validityDays, Long priceFen, Long originalPriceFen, Boolean transferable,
                              List<String> includes, String description, String operator) {
        CatalogProduct p = mustFindInStore(storeCode, id);
        String nm = requireName(name);
        String sc = p.getStoreCode() == null ? "" : p.getStoreCode();
        if (repo.existsByStoreCodeAndNameAndIdNot(sc, nm, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    (sc.isEmpty() ? "集团目录" : "本店") + "已存在同名商品「" + nm + "」");
        }
        p.setName(nm);
        p.setCategory(trimToNull(category));
        p.setSessions(nonNegative(sessions, 1));
        p.setValidityDays(requirePositive(validityDays, "有效期天数"));
        p.setPriceFen(nonNegativeFen(priceFen));
        p.setOriginalPriceFen(nonNegativeFen(originalPriceFen));
        p.setTransferable(transferable != null && transferable);
        p.setIncludes(toJson(includes));
        p.setDescription(trimToNull(description));
        p.setUpdatedBy(operator);
        p.setUpdatedAt(OffsetDateTime.now());
        repo.save(p);
        audit.record("CATALOG", "CATALOG-" + p.getId(), operator,
                "编辑" + typeLabel(p.getProductType()) + "「" + nm + "」",
                String.format("{\"storeCode\":%s,\"productCode\":%s,\"name\":%s,\"priceFen\":%d}",
                        jsonStr(sc), jsonStr(p.getProductCode()), jsonStr(nm), p.getPriceFen()));
    }

    /** 上架/下架切换：ON_SHELF ↔ OFF_SHELF。 */
    @Transactional
    public void toggle(String storeCode, Long id, String operator) {
        CatalogProduct p = mustFindInStore(storeCode, id);
        boolean onShelf = "OFF_SHELF".equals(p.getStatus());
        p.setStatus(onShelf ? "ON_SHELF" : "OFF_SHELF");
        p.setUpdatedBy(operator);
        p.setUpdatedAt(OffsetDateTime.now());
        repo.save(p);
        audit.record("CATALOG", "CATALOG-" + p.getId(), operator,
                (onShelf ? "上架" : "下架") + typeLabel(p.getProductType()) + "「" + p.getName() + "」",
                String.format("{\"storeCode\":%s,\"productCode\":%s,\"status\":%s}",
                        jsonStr(p.getStoreCode()), jsonStr(p.getProductCode()), jsonStr(p.getStatus())));
    }

    // ---- 种子（供 DataInitializer 复用，不写审计） ----

    /** 目录总条数（播种幂等门控用） */
    @Transactional(readOnly = true)
    public long countAll() {
        return repo.count();
    }

    @Transactional
    public CatalogProduct seedProduct(String storeCode, String productCode, String name, String productType,
                                      String category, int sessions, int validityDays, long priceYuan,
                                      long originalPriceYuan, boolean transferable, String status,
                                      List<String> includes, String description, String operator) {
        CatalogProduct p = new CatalogProduct();
        p.setStoreCode(storeCode == null ? "" : storeCode.trim());
        p.setProductCode(productCode);
        p.setName(name);
        p.setProductType(productType);
        p.setCategory(category);
        p.setSessions(sessions);
        p.setValidityDays(validityDays);
        p.setPriceFen(priceYuan * 100);
        p.setOriginalPriceFen(originalPriceYuan * 100);
        p.setTransferable(transferable);
        p.setStatus(status == null ? "ON_SHELF" : status);
        p.setIncludes(toJson(includes));
        p.setDescription(description);
        p.setCreatedBy(operator);
        p.setUpdatedBy(operator);
        return repo.save(p);
    }

    // ---- 出参装配 ----

    private Map<String, Object> row(CatalogProduct p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("storeCode", p.getStoreCode() == null ? "" : p.getStoreCode());
        row.put("productCode", p.getProductCode());
        row.put("code", p.getProductCode());
        row.put("name", p.getName());
        row.put("productType", p.getProductType());
        row.put("type", p.getProductType());
        row.put("category", p.getCategory());
        row.put("sessions", p.getSessions());
        row.put("validityDays", p.getValidityDays());
        row.put("priceYuan", fenToYuan(p.getPriceFen()));
        row.put("originalPriceYuan", fenToYuan(p.getOriginalPriceFen()));
        row.put("transferable", p.getTransferable());
        row.put("status", p.getStatus());
        row.put("includes", fromJson(p.getIncludes()));
        row.put("description", p.getDescription());
        row.put("updatedBy", p.getUpdatedBy());
        row.put("updatedAt", p.getUpdatedAt());
        return row;
    }

    // ---- 内部辅助 ----

    private CatalogProduct mustFindInStore(String storeCode, Long id) {
        CatalogProduct p = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在：" + id));
        String sc = storeCode == null ? "" : storeCode.trim();
        String rowSc = p.getStoreCode() == null ? "" : p.getStoreCode();
        boolean writable = sc.isEmpty() || rowSc.equals(sc);
        if (!writable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在或无权操作");
        }
        return p;
    }

    /** 编码生成：CARD→CD- / COURSE→CS-，同类全局序号 max+1，3 位补零。 */
    private String nextProductCode(String type) {
        String prefix = "CARD".equals(type) ? "CD-" : "CS-";
        int max = repo.findAllByOrderByProductCodeAsc().stream()
                .map(CatalogProduct::getProductCode)
                .filter(c -> c != null && c.startsWith(prefix))
                .mapToInt(c -> {
                    try {
                        return Integer.parseInt(c.substring(prefix.length()));
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .max().orElse(0);
        return prefix + String.format("%03d", max + 1);
    }

    private String normalizeType(String type) {
        if (isBlank(type)) throw badReq("请选择商品类型（卡项/疗程）");
        String t = type.trim().toUpperCase();
        if (!"CARD".equals(t) && !"COURSE".equals(t)) {
            throw badReq("商品类型非法：" + type);
        }
        return t;
    }

    private String normalizeStatus(String status) {
        if (isBlank(status)) return "ON_SHELF";
        String s = status.trim().toUpperCase();
        if (!"ON_SHELF".equals(s) && !"OFF_SHELF".equals(s)) {
            throw badReq("上下架状态非法：" + status);
        }
        return s;
    }

    private String requireName(String name) {
        if (isBlank(name)) throw badReq("商品名称不能为空");
        String nm = name.trim();
        if (nm.length() > 64) throw badReq("商品名称最长 64 字");
        return nm;
    }

    private int requirePositive(Integer v, String label) {
        if (v == null || v <= 0) throw badReq(label + "必须为正整数");
        return v;
    }

    private int nonNegative(Integer v, int dft) {
        if (v == null) return dft;
        return Math.max(0, v);
    }

    private static long nonNegativeFen(Long fen) {
        return fen == null ? 0L : Math.max(0L, fen);
    }

    private static double fenToYuan(Long fen) {
        return fen == null ? 0.0 : Math.round(fen) / 100.0;
    }

    private static String typeLabel(String type) {
        return "COURSE".equals(type) ? "疗程" : "卡项";
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** includes 列表 → JSON 数组字符串落库；null/空 → null。 */
    private String toJson(List<String> includes) {
        if (includes == null || includes.isEmpty()) return null;
        List<String> clean = includes.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim).toList();
        if (clean.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            log.warn("includes 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /** includes JSON 数组字符串 → 列表出参；兼容历史脏数据（空数组返回）。 */
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("includes 反序列化失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** JSON 字符串转义（审计 payload 手工拼 JSON，与 PricelistService.jsonStr 同口径） */
    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
