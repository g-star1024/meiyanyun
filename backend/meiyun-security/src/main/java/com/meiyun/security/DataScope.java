package com.meiyun.security;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 数据权限（data_scope）辅助器：按 {@link SecurityContext} 当前登录人的数据域，
 * 为 JPA 查询/单条断言产出过滤谓词。
 *
 * <p>数据域规则（多角色已在登录时取最大域）：
 * <ul>
 *   <li>无登录上下文（服务间匿名调用，如 name-map）→ 不过滤（开放）；</li>
 *   <li>GROUP / BRAND（超管、集团角色）→ 全量可见；</li>
 *   <li>REGION（区域经理）→ 可见 JWT stores 列表内门店数据；stores 为空（如未分配大区的财务）→ 全量；</li>
 *   <li>STORE（店长/医生/前台/运营）→ 仅本门店；storeCode 为空（数据异常）→ 不见任何数据；</li>
 *   <li>SELF（咨询师）→ 门店范围内且归属人为本人；ownerAttr 为空时退化为仅门店可见。</li>
 * </ul>
 *
 * 单条详情接口应使用 {@link #canReadStore(String)} / {@link #canReadOwned(String, String)}
 * 断言，越权统一按「数据不存在或无权查看」处理（不泄露存在性）。
 */
public final class DataScope {

    public static final String SCOPE_SELF = "SELF";
    public static final String SCOPE_STORE = "STORE";
    public static final String SCOPE_BRAND = "BRAND";
    public static final String SCOPE_REGION = "REGION";
    public static final String SCOPE_GROUP = "GROUP";

    private DataScope() {
    }

    /** 当前登录人；无上下文返回 null（服务间匿名通道）。 */
    public static LoginUser current() {
        return SecurityContext.get();
    }

    /** 门店维度 Specification（适用于客户/预约/订单/回访/计划/门店/员工/营收等带 storeCode 的实体）。 */
    public static <T> Specification<T> storeSpec(String storeAttr) {
        return (root, query, cb) -> {
            LoginUser u = SecurityContext.get();
            Predicate p = storePredicate(u, root.get(storeAttr), cb);
            return p != null ? p : cb.conjunction();
        };
    }

    /**
     * 门店 + 归属人维度 Specification（SELF 域用：客户 ownerStaffId、订单 consultant、咨询 consultant 等）。
     * SELF 时 AND(门店在域内, 归属人=本人)；其余域仅按门店谓词。
     */
    public static <T> Specification<T> ownedSpec(String storeAttr, String ownerAttr) {
        return (root, query, cb) -> {
            LoginUser u = SecurityContext.get();
            if (u == null) {
                return cb.conjunction();
            }
            List<Predicate> ps = new ArrayList<>();
            Predicate store = storePredicate(u, root.get(storeAttr), cb);
            if (store != null) {
                ps.add(store);
            }
            if (SCOPE_SELF.equals(u.scope()) && u.staffId() != null) {
                ps.add(cb.equal(root.get(ownerAttr), u.staffId()));
            }
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /**
     * 门店谓词：null 表示不限制（开放/集团/区域未分配）。
     * 注意：STORE 无 storeCode 时返回「永假」（disjunction），保证异常账号不见数据。
     */
    private static Predicate storePredicate(LoginUser u, Path<String> storePath, CriteriaBuilder cb) {
        if (u == null) {
            return null;
        }
        if (u.isSuper() || SCOPE_GROUP.equals(u.scope()) || SCOPE_BRAND.equals(u.scope())) {
            return null;
        }
        if (SCOPE_REGION.equals(u.scope())) {
            List<String> stores = u.stores();
            if (stores == null || stores.isEmpty()) {
                return null;
            }
            return storePath.in(stores);
        }
        // SELF / STORE：绑定本门店
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            return cb.disjunction();
        }
        return cb.equal(storePath, u.storeCode());
    }

    /** 单条数据（带门店）是否可读：详情接口越权断言用。 */
    public static boolean canReadStore(String dataStoreCode) {
        LoginUser u = SecurityContext.get();
        if (u == null) {
            return true;
        }
        if (u.isSuper() || SCOPE_GROUP.equals(u.scope()) || SCOPE_BRAND.equals(u.scope())) {
            return true;
        }
        if (SCOPE_REGION.equals(u.scope())) {
            List<String> stores = u.stores();
            return stores == null || stores.isEmpty() || stores.contains(dataStoreCode);
        }
        return u.storeCode() != null && u.storeCode().equals(dataStoreCode);
    }

    /** 单条数据（带门店 + 归属人）是否可读：SELF 域需归属人为本人。 */
    public static boolean canReadOwned(String dataStoreCode, String ownerStaffId) {
        if (!canReadStore(dataStoreCode)) {
            return false;
        }
        LoginUser u = SecurityContext.get();
        if (u != null && SCOPE_SELF.equals(u.scope()) && u.staffId() != null) {
            return u.staffId().equals(ownerStaffId);
        }
        return true;
    }

    /** 待办/审批视角：指定人是否为当前登录人（「我的待办」服务端过滤）。 */
    public static boolean isSelf(String staffId) {
        LoginUser u = SecurityContext.get();
        return u != null && u.staffId() != null && u.staffId().equals(staffId);
    }

    /** 当前登录人是否在指定工号集合内（指派人/会签人，逗号分隔或集合均可）。 */
    public static boolean isAmong(Collection<String> staffIds) {
        LoginUser u = SecurityContext.get();
        return u != null && u.staffId() != null && staffIds != null && staffIds.contains(u.staffId());
    }

    /**
     * 当前操作人工号：一律取 JWT 登录人（请求体 actor/operator/consultant 等字段不可信，忽略）；
     * 无登录上下文（服务间匿名调用）回落 "system"。审计留痕/申请人字段统一使用本方法。
     */
    public static String currentActor() {
        LoginUser u = SecurityContext.get();
        return (u == null || u.staffId() == null || u.staffId().isBlank()) ? "system" : u.staffId();
    }

    /** 当前登录人是否持有指定权限码（超管/通配 * 直接放行）；匿名无上下文返回 false。 */
    public static boolean hasPerm(String perm) {
        LoginUser u = SecurityContext.get();
        return u != null && u.hasPerm(perm);
    }
}
