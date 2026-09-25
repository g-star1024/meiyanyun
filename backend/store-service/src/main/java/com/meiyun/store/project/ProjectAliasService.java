package com.meiyun.store.project;

import com.meiyun.store.consumable.ConsumableAuditRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目别名映射域服务（P5-B96 卡1，DESIGN-T2 §3 D2）：泛化项目名 → SKU 两级命中映射。
 *
 * <p>全局别名（store_code IS NULL）集团级维护；门店级别名同 alias 优先命中。全局别名查重走
 * 应用层（PG 复合唯一约束对 NULL 行不生效，实体注释已标注）。CRUD 读走 {@code brand:view}、
 * 写走 {@code brand:edit}（复用 project 域既有权限码，零新造）；internal 端点仅供服务间调用。
 */
@Service
public class ProjectAliasService {

    private final ProjectAliasRepository aliasRepo;
    private final ProductSkuRepository skuRepo;
    private final ConsumableAuditRecorder audit;

    public ProjectAliasService(ProjectAliasRepository aliasRepo,
                               ProductSkuRepository skuRepo,
                               ConsumableAuditRecorder audit) {
        this.aliasRepo = aliasRepo;
        this.skuRepo = skuRepo;
        this.audit = audit;
    }

    /** 400 参数错误（中文） */
    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ---- CRUD ----

    /** 别名列表（storeCode/keyword 可选过滤；出参 join SKU 带 skuName/serviceCategory 便于展示）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAliases(String storeCode, String keyword) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        String kw = isBlank(keyword) ? null : keyword.trim().toLowerCase();
        Map<String, ProductSku> skuBySku = skuIndex();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectAlias a : aliasRepo.findAllByOrderByAliasAscIdAsc()) {
            if (sc != null && !sc.equals(a.getStoreCode())) continue;
            if (kw != null && !a.getAlias().toLowerCase().contains(kw)
                    && !a.getSku().toLowerCase().contains(kw)) continue;
            out.add(aliasRow(a, skuBySku));
        }
        return out;
    }

    /** 新建别名：alias/sku 必填；sku 不存在 400；同 scope（全局/同门店）重复 409。 */
    @Transactional
    public ProjectAlias createAlias(String alias, String storeCode, String sku, String remark, String operator) {
        if (isBlank(alias) || isBlank(sku)) {
            throw badReq("泛化项目名、指向 SKU 均不能为空");
        }
        String al = alias.trim();
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        String sk = sku.trim();
        mustFindSkuBySku(sk);
        scopeConflict(al, sc);
        ProjectAlias a = new ProjectAlias();
        a.setAlias(al);
        a.setStoreCode(sc);
        a.setSku(sk);
        a.setRemark(trimToNull(remark));
        a.setStatus("ACTIVE");
        a.setCreatedBy(operator);
        a.setUpdatedBy(operator);
        aliasRepo.save(a);
        audit.record("BRAND", "ALIAS-" + a.getId(), operator, "新建项目别名",
                String.format("{\"alias\":%s,\"storeCode\":%s,\"sku\":%s}",
                        jsonStr(a.getAlias()), jsonStr(a.getStoreCode()), jsonStr(a.getSku())));
        return a;
    }

    /** 更新别名（null 不覆盖；alias/storeCode 建档后不可改，换映射请删旧建新）；不存在 404。 */
    @Transactional
    public void updateAlias(Long id, String sku, String remark, String operator) {
        ProjectAlias a = mustFind(id);
        if (sku != null && !sku.isBlank()) {
            mustFindSkuBySku(sku.trim());
            a.setSku(sku.trim());
        }
        if (remark != null) a.setRemark(trimToNull(remark));
        a.setUpdatedBy(operator);
        a.setUpdatedAt(OffsetDateTime.now());
        aliasRepo.save(a);
        audit.record("BRAND", "ALIAS-" + a.getId(), operator, "更新项目别名",
                String.format("{\"alias\":%s,\"sku\":%s}", jsonStr(a.getAlias()), jsonStr(a.getSku())));
    }

    /** 别名启停：ACTIVE/INACTIVE；已是目标态幂等返回不审计。 */
    @Transactional
    public void setAliasStatus(Long id, String status, String operator) {
        String st = normalizeStatus(status);
        ProjectAlias a = mustFind(id);
        if (st.equals(a.getStatus())) return;
        a.setStatus(st);
        a.setUpdatedBy(operator);
        a.setUpdatedAt(OffsetDateTime.now());
        aliasRepo.save(a);
        audit.record("BRAND", "ALIAS-" + a.getId(), operator, "项目别名启停",
                String.format("{\"alias\":%s,\"status\":%s}", jsonStr(a.getAlias()), jsonStr(st)));
    }

    /** 删除别名（映射关系无历史价值，物理删除＋审计留痕）；不存在 404。 */
    @Transactional
    public void deleteAlias(Long id, String operator) {
        ProjectAlias a = mustFind(id);
        aliasRepo.delete(a);
        audit.record("BRAND", "ALIAS-" + id, operator, "删除项目别名",
                String.format("{\"alias\":%s,\"storeCode\":%s,\"sku\":%s}",
                        jsonStr(a.getAlias()), jsonStr(a.getStoreCode()), jsonStr(a.getSku())));
    }

    // ---- 内部（服务间） ----

    /**
     * ACTIVE 别名全量行（internal 端点用）：[{alias, storeCode, sku, skuName, serviceCategory}]。
     * 消费方（txn StoreProjectClient）全量拉取后自建缓存做两级命中：storeCode 精确 → 全局兜底。
     * sku 被删时 serviceCategory 为 null 如实带出（不炸调用方，命中率验收时暴露漂移）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeAliasRows() {
        Map<String, ProductSku> skuBySku = skuIndex();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectAlias a : aliasRepo.findByStatusOrderByAliasAscIdAsc("ACTIVE")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("alias", a.getAlias());
            row.put("storeCode", a.getStoreCode());
            row.put("sku", a.getSku());
            ProductSku s = skuBySku.get(a.getSku());
            row.put("skuName", s == null ? null : s.getName());
            row.put("serviceCategory", s == null ? null : s.getServiceCategory());
            out.add(row);
        }
        return out;
    }

    // ---- 种子（供 DataInitializer 复用，不写审计） ----

    @Transactional
    public ProjectAlias seedAlias(String alias, String storeCode, String sku, String remark, String operator) {
        ProjectAlias a = new ProjectAlias();
        a.setAlias(alias);
        a.setStoreCode(storeCode);
        a.setSku(sku);
        a.setRemark(remark);
        a.setStatus("ACTIVE");
        a.setCreatedBy(operator);
        a.setUpdatedBy(operator);
        return aliasRepo.save(a);
    }

    // ---- 出参装配 ----

    private Map<String, Object> aliasRow(ProjectAlias a, Map<String, ProductSku> skuBySku) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.getId());
        row.put("alias", a.getAlias());
        row.put("storeCode", a.getStoreCode());
        row.put("scope", a.getStoreCode() == null ? "GLOBAL" : "STORE");
        row.put("sku", a.getSku());
        ProductSku s = skuBySku.get(a.getSku());
        row.put("skuName", s == null ? null : s.getName());
        row.put("serviceCategory", s == null ? null : s.getServiceCategory());
        row.put("status", a.getStatus());
        row.put("remark", a.getRemark());
        row.put("createdAt", a.getCreatedAt());
        return row;
    }

    // ---- 内部辅助 ----

    private Map<String, ProductSku> skuIndex() {
        return skuRepo.findAll().stream()
                .collect(Collectors.toMap(ProductSku::getSku, Function.identity(), (a, b) -> a));
    }

    private ProjectAlias mustFind(Long id) {
        return aliasRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目别名不存在：" + id));
    }

    private void mustFindSkuBySku(String sku) {
        if (skuRepo.findBySku(sku).isEmpty()) {
            throw badReq("指向 SKU 不存在：" + sku);
        }
    }

    private void scopeConflict(String alias, String storeCode) {
        boolean dup = storeCode == null
                ? aliasRepo.findByAliasAndStoreCodeIsNull(alias).isPresent()
                : aliasRepo.findByAliasAndStoreCode(alias, storeCode).isPresent();
        if (dup) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "别名「" + alias + "」在" + (storeCode == null ? "全局" : "门店 " + storeCode)
                            + " 已存在，请勿重复建档");
        }
    }

    private static String normalizeStatus(String status) {
        if (isBlank(status)) throw badReq("状态不能为空");
        String st = status.trim();
        if (!List.of("ACTIVE", "INACTIVE").contains(st)) {
            throw badReq("状态仅支持 ACTIVE/INACTIVE");
        }
        return st;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** JSON 字符串转义（审计 payload 手工拼 JSON，与 ProjectService.jsonStr 同口径） */
    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
