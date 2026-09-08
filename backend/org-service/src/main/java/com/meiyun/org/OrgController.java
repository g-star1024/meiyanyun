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
    private final StaffRoleRepository staffRoleRepo;

    public OrgController(TenantRepository tenantRepo, OrgUnitRepository orgRepo,
                         RoleDefRepository roleRepo, StaffRepository staffRepo,
                         StaffRoleRepository staffRoleRepo) {
        this.tenantRepo = tenantRepo;
        this.orgRepo = orgRepo;
        this.roleRepo = roleRepo;
        this.staffRepo = staffRepo;
        this.staffRoleRepo = staffRoleRepo;
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

    /**
     * 员工列表（双签复核人 / 审批人候选 + 员工管理）。
     * 数据域强制注入：SELF/STORE 只见本店；REGION 见本区门店 + 大区编制账号（区域经理等无门店
     * 编制人员，store_code 为空、region 有值，B19 三阶段审批/转交/加签候选来源）；GROUP/BRAND 全量。
     * roleCode 匹配主角色 + staff_role 兼岗并集；region 参数按 staff.region 过滤（REGION 域强制
     * 回收到登录人大区，禁止跨区取人）。
     */
    @GetMapping("/staff")
    @RequirePerm({"rbac:view", "appointment:view"})
    public List<Staff> staff(@RequestParam(required = false) String storeCode,
                             @RequestParam(required = false) String roleCode,
                             @RequestParam(required = false) String region) {
        var u = DataScope.current();
        // 门店编制账号：走通用门店数据域（STORE 本店 / REGION 本区门店 / GROUP 全量）
        Specification<Staff> spec = DataScope.storeSpec("storeCode");
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        String regionParam = (region != null && !region.isBlank()) ? region.trim() : null;
        // REGION 域：region 参数强制回收到登录人大区，防止跨区查人
        if (u != null && DataScope.SCOPE_REGION.equals(u.scope()) && u.region() != null && !u.region().isBlank()) {
            regionParam = u.region();
        }
        final String regionFilter = regionParam;
        if (regionFilter != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("region"), regionFilter));
        }
        List<Staff> merged = new ArrayList<>(staffRepo.findAll(spec, Sort.by("staffId")));

        // 大区编制账号（store_code 为空：区域经理/区域财务/集团岗）——storeSpec 只按门店列过滤，
        // 天然排除这批人；按数据域并入候选：REGION 见本区，GROUP/BRAND 及无上下文全量，STORE/SELF 不见
        List<Staff> headStaff;
        if (u == null || u.isSuper() || DataScope.SCOPE_GROUP.equals(u.scope()) || DataScope.SCOPE_BRAND.equals(u.scope())) {
            Specification<Staff> headSpec = (root, q, cb) -> cb.isNull(root.get("storeCode"));
            if (regionFilter != null) {
                headSpec = headSpec.and((root, q, cb) -> cb.equal(root.get("region"), regionFilter));
            }
            headStaff = staffRepo.findAll(headSpec, Sort.by("staffId"));
        } else if (DataScope.SCOPE_REGION.equals(u.scope()) && u.region() != null && !u.region().isBlank()) {
            final String myRegion = u.region();
            headStaff = staffRepo.findAll((root, q, cb) ->
                    cb.and(cb.isNull(root.get("storeCode")), cb.equal(root.get("region"), myRegion)),
                    Sort.by("staffId"));
        } else {
            headStaff = List.of();
        }
        for (Staff s : headStaff) {
            if (merged.stream().noneMatch(x -> x.getStaffId().equals(s.getStaffId()))) {
                merged.add(s);
            }
        }

        // roleCode 匹配主角色 + staff_role 兼岗并集（主角色不同但兼岗持该角色的员工同样入选）
        List<Staff> list = merged;
        if (roleCode != null && !roleCode.isBlank()) {
            Set<String> byRole = new HashSet<>();
            staffRoleRepo.findByRoleCode(roleCode.trim()).forEach(r -> byRole.add(r.getStaffId()));
            final String roleFilter = roleCode.trim();
            list = merged.stream()
                    .filter(s -> roleFilter.equals(s.getRoleCode()) || byRole.contains(s.getStaffId()))
                    .sorted(Comparator.comparing(Staff::getStaffId))
                    .collect(Collectors.toList());
        }
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
    @RequirePerm("internal:name-map")
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

    /**
     * 员工档案单查（服务间调用专用，B18 划扣双签复核人硬校验）：GET /api/org/internal/staff/{id}。
     * 返回工号/姓名/主角色/全量角色（staff_role 并集）/在职状态/门店；无 DataScope——调用方（txn）
     * 按真实工号做存在性、在职、角色闸门校验，校验不过不得执行划扣（与 name-map 只读降级不同，
     * 本端点不可用时 txn 侧按 502 硬失败）。员工不存在 404，由调用方转 400 中文提示。
     */
    @GetMapping("/internal/staff/{id}")
    @RequirePerm("internal:name-map")
    public Map<String, Object> internalStaffProfile(@PathVariable String id) {
        Staff s = staffRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "员工不存在: " + id));
        List<String> roles = staffRoleRepo.findByStaffId(id).stream()
                .map(StaffRole::getRoleCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("staffId", s.getStaffId());
        m.put("staffName", s.getStaffName());
        m.put("primaryRole", s.getRoleCode());
        m.put("roles", roles);
        m.put("status", s.getStatus());
        m.put("storeCode", s.getStoreCode());
        m.put("region", s.getRegion());
        return m;
    }

    /**
     * 按角色列在职员工（服务间调用专用，B20 审批 SLA 催办目标人解析）：
     * GET /api/org/internal/staff/by-role?roleCode=FINANCE&storeCode=SST01&region=华东。
     *
     * <p>命中口径与公开 /staff 一致——主角色 staff.role_code 或兼岗 staff_role.role_code 任一命中即返回，
     * 且仅返回在职（status=在职）。无 DataScope（系统内部任务使用，调用方持 internal:name-map）。
     * storeCode / region 为可选收敛过滤：REGION_MGR 无门店归属，调方传 storeCode 时此处先把门店
     * 解析为区域再按区域过滤；STORE_MGR / FINANCE 直接忽略 region（前者按门店、后者全量）。
     */
    @GetMapping("/internal/staff/by-role")
    @RequirePerm("internal:name-map")
    public List<Map<String, Object>> internalStaffByRole(@RequestParam("roleCode") String roleCode,
                                                         @RequestParam(value = "storeCode", required = false) String storeCode,
                                                         @RequestParam(value = "region", required = false) String region) {
        String role = roleCode == null ? "" : roleCode.trim();
        if (role.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleCode 必填");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        staffRepo.findByRoleCodeOrderByStaffIdAsc(role).stream()
                .map(Staff::getStaffId).forEach(ids::add);
        staffRoleRepo.findByRoleCode(role).stream()
                .map(StaffRole::getStaffId).forEach(ids::add);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Staff s : staffRepo.findAllById(ids)) {
            if (!"在职".equals(s.getStatus())) continue;
            if (storeCode != null && !storeCode.isBlank()) {
                if ("REGION_MGR".equals(role)) {
                    String storeRegion = regionOfStore(storeCode.trim());
                    if (storeRegion == null || !storeRegion.equals(s.getRegion())) continue;
                } else if (!storeCode.trim().equals(s.getStoreCode())) {
                    continue;
                }
            } else if (region != null && !region.isBlank() && !region.trim().equals(s.getRegion())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("staffId", s.getStaffId());
            m.put("staffName", s.getStaffName());
            m.put("primaryRole", s.getRoleCode());
            m.put("status", s.getStatus());
            m.put("storeCode", s.getStoreCode());
            m.put("region", s.getRegion());
            out.add(m);
        }
        return out;
    }

    /** 门店码 → 所属区域（org_unit 门店节点 region 列，org_code 为 O- 前缀、store_code 为门店码）；找不到返回 null。 */
    private String regionOfStore(String storeCode) {
        return orgRepo.findFirstByStoreCode(storeCode).map(OrgUnit::getRegion).orElse(null);
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
