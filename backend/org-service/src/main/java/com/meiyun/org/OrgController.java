package com.meiyun.org;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * org-service 控制器：租户 / 组织树（集团→六区→23门店）/ RBAC 角色矩阵 / 员工。
 *
 * 数据权限三级（data_scope）：门店 / 区域 / 集团。
 * 岗位互斥：双签「两签不得同一岗位序列」的 role_sequence 由角色定义提供。
 */
@RestController
@RequestMapping("/api/org")
public class OrgController {

    private final TenantRepository tenantRepo;
    private final OrgUnitRepository orgRepo;
    private final RoleDefRepository roleRepo;
    private final StaffRepository staffRepo;

    public OrgController(TenantRepository tenantRepo, OrgUnitRepository orgRepo,
                         RoleDefRepository roleRepo, StaffRepository staffRepo) {
        this.tenantRepo = tenantRepo;
        this.orgRepo = orgRepo;
        this.roleRepo = roleRepo;
        this.staffRepo = staffRepo;
    }

    // ==================== 租户 ====================

    @GetMapping("/tenant/{id}")
    @RequirePerm("tenant:view")
    public Tenant tenant(@PathVariable String id) {
        return tenantRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在: " + id));
    }

    @GetMapping("/tenants")
    @RequirePerm("tenant:view")
    public List<Tenant> tenants() {
        return tenantRepo.findAll();
    }

    // ==================== 组织树 ====================

    /** 组织树三级结构（集团 → 区域 → 门店），children 递归嵌套。 */
    @GetMapping("/tree")
    @RequirePerm("org:view")
    public Map<String, Object> tree() {
        List<OrgUnit> all = orgRepo.findAllByOrderBySortNoAsc();
        Map<String, OrgUnit> byCode = all.stream()
                .collect(Collectors.toMap(OrgUnit::getOrgCode, Function.identity()));
        Map<String, List<Map<String, Object>>> childrenByParent = new LinkedHashMap<>();
        for (OrgUnit u : all) {
            if (u.getParentCode() != null) {
                childrenByParent.computeIfAbsent(u.getParentCode(), k -> new ArrayList<>())
                        .add(node(u));
            }
        }
        // 自顶向下挂接
        OrgUnit root = all.stream().filter(u -> u.getParentCode() == null)
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "无组织根节点"));
        return build(root, childrenByParent, byCode);
    }

    /** 六大区聚合（区域赛马用）：每区域门店数 / 营业中数 / 直营数。 */
    @GetMapping("/regions")
    @RequirePerm("org:view")
    public List<Map<String, Object>> regions() {
        List<OrgUnit> stores = orgRepo.findByOrgTypeOrderBySortNoAsc("门店");
        Map<String, Map<String, Object>> acc = new LinkedHashMap<>();
        for (OrgUnit s : stores) {
            Map<String, Object> m = acc.computeIfAbsent(s.getRegion(), k -> {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("region", k);
                v.put("storeCount", 0);
                return v;
            });
            m.put("storeCount", (int) m.get("storeCount") + 1);
        }
        return new ArrayList<>(acc.values());
    }

    @GetMapping("/org-units")
    @RequirePerm("org:view")
    public List<OrgUnit> orgUnits(@RequestParam(required = false) String orgType,
                                  @RequestParam(required = false) String region) {
        if (orgType != null) return orgRepo.findByOrgTypeOrderBySortNoAsc(orgType);
        if (region != null) return orgRepo.findByRegionOrderBySortNoAsc(region);
        return orgRepo.findAllByOrderBySortNoAsc();
    }

    // ==================== RBAC 角色矩阵 ====================

    @GetMapping("/roles")
    @RequirePerm("role:view")
    public List<RoleDef> roles() {
        return roleRepo.findAllByOrderByRoleCodeAsc();
    }

    /** 角色 × 数据权限矩阵汇总（门店/区域/集团三级各几角色）。 */
    @GetMapping("/role-matrix")
    @RequirePerm("role:view")
    public Map<String, Long> roleMatrix() {
        return roleRepo.findAll().stream()
                .collect(Collectors.groupingBy(RoleDef::getDataScope, Collectors.counting()));
    }

    // ==================== 员工 ====================

    @GetMapping("/staff")
    @RequirePerm({"rbac:view", "appointment:view"})
    public List<Staff> staff(@RequestParam(required = false) String storeCode,
                             @RequestParam(required = false) String roleCode) {
        // 数据域强制注入：SELF/STORE 只见本店，REGION 见本区门店（区域经理等无门店编制的账号不在列表内），GROUP/BRAND 全量
        Specification<Staff> spec = DataScope.storeSpec("storeCode");
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (roleCode != null && !roleCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("roleCode"), roleCode));
        }
        List<Staff> list = staffRepo.findAll(spec, Sort.by("staffId"));
        Map<String, RoleDef> roles = roleRepo.findAll().stream()
                .collect(Collectors.toMap(RoleDef::getRoleCode, Function.identity()));
        list.forEach(s -> s.setRole(roles.get(s.getRoleCode())));
        return list;
    }

    @GetMapping("/staff/{id}")
    @RequirePerm({"rbac:view", "appointment:view"})
    public Staff staffById(@PathVariable String id) {
        Staff s = staffRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        // 详情数据域：门店归属校验；无门店编制的员工（区域/集团岗）仅本人或 GROUP 可见
        boolean visible = DataScope.canReadStore(s.getStoreCode())
                || DataScope.isSelf(s.getStaffId());
        if (!visible) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        roleRepo.findById(s.getRoleCode()).ifPresent(s::setRole);
        return s;
    }

    /**
     * 批量员工名解析（服务间调用专用）：staff_id → staff_name。
     * 供 customer / txn 等服务把订单咨询师、预约医生、客户归属员工等工号解析为中文名，
     * 替代各服务直连 staff 表的权宜做法（微服务拆库后依然成立）。
     * 例：GET /api/org/staff/name-map?ids=SE006,SE007 → {"SE006":"沈咨询","SE007":"古医生"}
     */
    @GetMapping("/staff/name-map")
    public Map<String, String> staffNameMap(@RequestParam(value = "ids", required = false) List<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ids == null) return out;
        List<String> distinct = ids.stream()
                .filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        if (distinct.isEmpty()) return out;
        staffRepo.findAllById(distinct)
                .forEach(s -> out.put(s.getStaffId(), s.getStaffName()));
        return out;
    }

    // ==================== 内部方法 ====================

    private Map<String, Object> node(OrgUnit u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orgCode", u.getOrgCode());
        m.put("orgName", u.getOrgName());
        m.put("orgType", u.getOrgType());
        if (u.getStoreCode() != null) m.put("storeCode", u.getStoreCode());
        if (u.getRegion() != null) m.put("region", u.getRegion());
        return m;
    }

    private Map<String, Object> build(OrgUnit u,
                                      Map<String, List<Map<String, Object>>> childrenByParent,
                                      Map<String, OrgUnit> byCode) {
        Map<String, Object> m = node(u);
        List<Map<String, Object>> kids = childrenByParent.get(u.getOrgCode());
        if (kids != null && !kids.isEmpty()) {
            // 递归把子节点的 children 挂上
            List<Map<String, Object>> nested = new ArrayList<>();
            for (Map<String, Object> k : kids) {
                OrgUnit child = byCode.get(k.get("orgCode"));
                nested.add(build(child, childrenByParent, byCode));
            }
            m.put("children", nested);
        }
        return m;
    }
}
