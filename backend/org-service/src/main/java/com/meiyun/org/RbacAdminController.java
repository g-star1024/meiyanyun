package com.meiyun.org;

import com.meiyun.org.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.PasswordEncoder;
import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RBAC 管理端点（用户/角色/权限管理页真实数据源）：
 * 员工生命周期（创建/停用/重置密码/调店/主角色调整/兼岗）、角色 CRUD 与权限覆写、
 * 角色成员与权限矩阵的聚合只读视图。
 *
 * <p>权限边界：员工管理写动作持 rbac:edit（区域经理/店长/财务/超管）；角色管理写动作持
 * role:create/edit/delete/assign（区域经理/超管）；读视图持 role:view / rbac:view。
 * 数据域：门店店长仅可管理本店员工，区域经理限本区，集团全量；越权按 404/400 中文返回。
 *
 * <p>内置 8 角色（CANONICAL_ROLES，权限矩阵权威源）受保护：不可删除、不可改权限，
 * 重启即由 RbacDataInitializer 增量幂等恢复；自定义角色全量 CRUD。
 */
@RestController
@RequestMapping("/api/org")
public class RbacAdminController {

    /** 内置矩阵角色码（与 RbacDataInitializer.CANONICAL_ROLES 同源）：禁删、禁改权限、成员禁摘除。 */
    private static final Set<String> BUILTIN_ROLES = Set.of(
            "SUPER_ADMIN", "REGION_MGR", "STORE_MGR", "CONSULTANT",
            "DOCTOR", "FRONT_DESK", "OPERATOR", "FINANCE");

    private final StaffRepository staffRepo;
    private final RoleDefRepository roleRepo;
    private final StaffRoleRepository staffRoleRepo;
    private final RolePermissionRepository rolePermRepo;
    private final PermissionDefRepository permRepo;
    private final OrgUnitRepository orgRepo;
    private final AuditRecorder audit;

    public RbacAdminController(StaffRepository staffRepo, RoleDefRepository roleRepo,
                               StaffRoleRepository staffRoleRepo, RolePermissionRepository rolePermRepo,
                               PermissionDefRepository permRepo, OrgUnitRepository orgRepo,
                               AuditRecorder audit) {
        this.staffRepo = staffRepo;
        this.roleRepo = roleRepo;
        this.staffRoleRepo = staffRoleRepo;
        this.rolePermRepo = rolePermRepo;
        this.permRepo = permRepo;
        this.orgRepo = orgRepo;
        this.audit = audit;
    }

    // ==================== 员工管理 ====================

    /** 新建员工：工号/姓名/主角色必填；loginName 默认=工号，默认密码 meiyun123（PBKDF2），状态在职。 */
    @PostMapping("/admin/staff")
    @RequirePerm("rbac:edit")
    @Transactional
    public Staff createStaff(@RequestBody StaffCreateRequest req) {
        if (req.staffId() == null || req.staffId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工号不能为空");
        }
        if (req.staffName() == null || req.staffName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "员工姓名不能为空");
        }
        if (req.roleCode() == null || req.roleCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主角色不能为空");
        }
        String staffId = req.staffId().trim();
        if (staffRepo.existsById(staffId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工号已存在: " + staffId);
        }
        RoleDef role = roleRepo.findById(req.roleCode().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在: " + req.roleCode()));
        assertRoleUsable(role);
        String storeCode = req.storeCode() == null || req.storeCode().isBlank() ? null : req.storeCode().trim();
        if (storeCode != null) {
            assertStoreExists(storeCode);
            if (!DataScope.canReadStore(storeCode)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权在该门店创建员工: " + storeCode);
            }
        }
        Staff s = new Staff();
        s.setStaffId(staffId);
        s.setStaffName(req.staffName().trim());
        s.setRoleCode(role.getRoleCode());
        s.setStoreCode(storeCode);
        s.setRegion(req.region() == null || req.region().isBlank() ? null : req.region().trim());
        s.setMedicalLicensed(Boolean.TRUE.equals(req.medicalLicensed()));
        s.setStatus("在职");
        s.setLoginName(staffId);
        s.setPasswordHash(PasswordEncoder.hash(RbacDataInitializer.DEFAULT_PASSWORD));
        s.setCreatedAt(OffsetDateTime.now());
        s.setRole(role);
        staffRepo.save(s);
        // 主角色同步进一人多角色关联表
        staffRoleRepo.save(new StaffRole(staffId, role.getRoleCode()));
        audit.record("STAFF", staffId, DataScope.currentActor(), "CREATE",
                "{\"staffId\":\"" + staffId + "\",\"staffName\":\"" + esc(s.getStaffName())
                        + "\",\"roleCode\":\"" + role.getRoleCode()
                        + "\",\"storeCode\":" + jsonStr(storeCode) + "}");
        return s;
    }

    /** 停用员工（status→离职，登录即被拒）；已离职幂等返回、不重复记审计。 */
    @PostMapping("/admin/staff/{id}/disable")
    @RequirePerm("rbac:edit")
    @Transactional
    public Staff disableStaff(@PathVariable String id) {
        Staff s = getManageableStaff(id);
        if ("离职".equals(s.getStatus())) {
            roleRepo.findById(s.getRoleCode()).ifPresent(s::setRole);
            return s;
        }
        s.setStatus("离职");
        staffRepo.save(s);
        roleRepo.findById(s.getRoleCode()).ifPresent(s::setRole);
        audit.record("STAFF", id, DataScope.currentActor(), "DISABLE",
                "{\"staffId\":\"" + id + "\",\"staffName\":\"" + esc(s.getStaffName()) + "\"}");
        return s;
    }

    /** 重置密码为默认 meiyun123（员工下次登录后自行改密；生产应走强制改密流程）。 */
    @PostMapping("/admin/staff/{id}/reset-password")
    @RequirePerm("rbac:edit")
    @Transactional
    public Map<String, Object> resetPassword(@PathVariable String id) {
        Staff s = getManageableStaff(id);
        s.setPasswordHash(PasswordEncoder.hash(RbacDataInitializer.DEFAULT_PASSWORD));
        staffRepo.save(s);
        audit.record("STAFF", id, DataScope.currentActor(), "RESET_PASSWORD",
                "{\"staffId\":\"" + id + "\",\"staffName\":\"" + esc(s.getStaffName()) + "\"}");
        return Map.of("staffId", id, "reset", true, "defaultPassword", RbacDataInitializer.DEFAULT_PASSWORD);
    }

    /** 调店/调大区：门店编码须存在且在操作人数据域内；大区可空（集团职能口径）。 */
    @PostMapping("/admin/staff/{id}/transfer")
    @RequirePerm("rbac:edit")
    @Transactional
    public Staff transferStaff(@PathVariable String id, @RequestBody StaffTransferRequest req) {
        Staff s = getManageableStaff(id);
        String targetStore = req.storeCode() == null || req.storeCode().isBlank() ? null : req.storeCode().trim();
        if (targetStore != null) {
            assertStoreExists(targetStore);
            if (!DataScope.canReadStore(targetStore)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权调入该门店: " + targetStore);
            }
        }
        String oldStore = s.getStoreCode();
        s.setStoreCode(targetStore);
        s.setRegion(req.region() == null || req.region().isBlank() ? null : req.region().trim());
        staffRepo.save(s);
        roleRepo.findById(s.getRoleCode()).ifPresent(s::setRole);
        audit.record("STAFF", id, DataScope.currentActor(), "TRANSFER",
                "{\"staffId\":\"" + id + "\",\"fromStore\":" + jsonStr(oldStore)
                        + ",\"toStore\":" + jsonStr(targetStore) + "}");
        return s;
    }

    /** 调整主角色：staff.role_code 与 staff_role 关联同步；角色须存在。 */
    @PostMapping("/admin/staff/{id}/primary-role")
    @RequirePerm({"rbac:edit", "role:assign"})
    @Transactional
    public Staff changePrimaryRole(@PathVariable String id, @RequestBody RoleAssignRequest req) {
        if (req.roleCode() == null || req.roleCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色编码不能为空");
        }
        Staff s = getManageableStaff(id);
        String roleCode = req.roleCode().trim();
        RoleDef role = roleRepo.findById(roleCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在: " + roleCode));
        assertRoleUsable(role);
        String oldRole = s.getRoleCode();
        if (roleCode.equals(oldRole)) {
            s.setRole(role);
            return s;
        }
        s.setRoleCode(roleCode);
        staffRepo.save(s);
        staffRoleRepo.save(new StaffRole(id, roleCode));
        s.setRole(role);
        audit.record("STAFF", id, DataScope.currentActor(), "PRIMARY_ROLE",
                "{\"staffId\":\"" + id + "\",\"fromRole\":\"" + oldRole
                        + "\",\"toRole\":\"" + roleCode + "\"}");
        return s;
    }

    /** 追加兼岗角色（一人多角色）：幂等，已存在直接返回；内置/自定义角色均可兼。 */
    @PostMapping("/admin/staff/{id}/roles")
    @RequirePerm("role:assign")
    @Transactional
    public Map<String, Object> addStaffRole(@PathVariable String id, @RequestBody RoleAssignRequest req) {
        if (req.roleCode() == null || req.roleCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色编码不能为空");
        }
        Staff s = getManageableStaff(id);
        String roleCode = req.roleCode().trim();
        RoleDef role = roleRepo.findById(roleCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在: " + roleCode));
        assertRoleUsable(role);
        StaffRole.Key key = new StaffRole.Key(id, roleCode);
        boolean added = staffRoleRepo.findById(key).isEmpty();
        if (added) {
            staffRoleRepo.save(new StaffRole(id, roleCode));
            audit.record("STAFF", id, DataScope.currentActor(), "ROLE_GRANT",
                    "{\"staffId\":\"" + id + "\",\"roleCode\":\"" + roleCode + "\"}");
        }
        return Map.of("staffId", id, "roleCode", roleCode, "added", added);
    }

    /** 摘除兼岗角色：主角色不可摘（请先调整主角色）；内置角色仅当非主角色时可摘。 */
    @DeleteMapping("/admin/staff/{id}/roles/{roleCode}")
    @RequirePerm("role:assign")
    @Transactional
    public Map<String, Object> removeStaffRole(@PathVariable String id, @PathVariable String roleCode) {
        Staff s = getManageableStaff(id);
        if (roleCode.equals(s.getRoleCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主角色不可摘除，请先调整主角色");
        }
        boolean removed = staffRoleRepo.findById(new StaffRole.Key(id, roleCode))
                .map(sr -> {
                    staffRoleRepo.delete(sr);
                    return true;
                }).orElse(false);
        if (removed) {
            audit.record("STAFF", id, DataScope.currentActor(), "ROLE_REVOKE",
                    "{\"staffId\":\"" + id + "\",\"roleCode\":\"" + roleCode + "\"}");
        }
        return Map.of("staffId", id, "roleCode", roleCode, "removed", removed);
    }

    // ==================== 角色管理 ====================

    /** 新建自定义角色：编码/名称/数据域必填；数据域限 门店/区域/集团 三档。 */
    @PostMapping("/admin/roles")
    @RequirePerm("role:create")
    @Transactional
    public RoleDef createRole(@RequestBody RoleCreateRequest req) {
        if (req.roleCode() == null || req.roleCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色编码不能为空");
        }
        if (req.roleName() == null || req.roleName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色名称不能为空");
        }
        String code = req.roleCode().trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,23}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "角色编码需为 2-24 位大写字母/数字/下划线，且以字母开头");
        }
        if (BUILTIN_ROLES.contains(code) || roleRepo.existsById(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色编码已存在: " + code);
        }
        String dataScope = req.dataScope() == null || req.dataScope().isBlank() ? "门店" : req.dataScope().trim();
        if (!Set.of("门店", "区域", "集团").contains(dataScope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据域仅支持：门店 / 区域 / 集团");
        }
        RoleDef r = new RoleDef();
        r.setRoleCode(code);
        r.setRoleName(req.roleName().trim());
        r.setDataScope(dataScope);
        r.setRoleSequence(req.roleSequence() == null || req.roleSequence().isBlank() ? "90" : req.roleSequence().trim());
        r.setMedical(Boolean.TRUE.equals(req.medical()));
        r.setDescription(req.description() == null ? "" : req.description().trim());
        r.setStatus("启用");
        roleRepo.save(r);
        audit.record("ROLE", code, DataScope.currentActor(), "CREATE",
                "{\"roleCode\":\"" + code + "\",\"roleName\":\"" + esc(r.getRoleName())
                        + "\",\"dataScope\":\"" + dataScope + "\"}");
        return r;
    }

    /** 更新角色定义：内置角色仅可改描述展示字段，数据域/序列/医疗岗以矩阵为准不可改。 */
    @PutMapping("/admin/roles/{code}")
    @RequirePerm("role:edit")
    @Transactional
    public RoleDef updateRole(@PathVariable String code, @RequestBody RoleUpdateRequest req) {
        RoleDef r = roleRepo.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在: " + code));
        boolean builtin = BUILTIN_ROLES.contains(code);
        if (req.roleName() != null && !req.roleName().isBlank()) {
            if (builtin) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置角色名称不可修改");
            }
            r.setRoleName(req.roleName().trim());
        }
        if (req.description() != null) {
            r.setDescription(req.description().trim());
        }
        if (!builtin) {
            if (req.dataScope() != null && !req.dataScope().isBlank()) {
                String ds = req.dataScope().trim();
                if (!Set.of("门店", "区域", "集团").contains(ds)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据域仅支持：门店 / 区域 / 集团");
                }
                r.setDataScope(ds);
            }
            if (req.roleSequence() != null && !req.roleSequence().isBlank()) {
                r.setRoleSequence(req.roleSequence().trim());
            }
            if (req.medical() != null) {
                r.setMedical(req.medical());
            }
        }
        roleRepo.save(r);
        audit.record("ROLE", code, DataScope.currentActor(), "UPDATE",
                "{\"roleCode\":\"" + code + "\",\"roleName\":\"" + esc(r.getRoleName())
                        + "\",\"builtin\":" + builtin + "}");
        return r;
    }

    /**
     * 停用/启用角色（自定义角色）：停用后不可再授予员工（建员工/调主角色/兼岗均拦截），
     * 已授予关系保留、随角色再次启用自动恢复有效；内置矩阵角色恒启用、不可停用。
     */
    @PostMapping("/admin/roles/{code}/toggle-status")
    @RequirePerm("role:edit")
    @Transactional
    public RoleDef toggleRoleStatus(@PathVariable String code) {
        RoleDef r = roleRepo.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在: " + code));
        if (BUILTIN_ROLES.contains(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置矩阵角色不可停用: " + code);
        }
        boolean targetDisabled = !"停用".equals(r.getStatus());
        r.setStatus(targetDisabled ? "停用" : "启用");
        roleRepo.save(r);
        audit.record("ROLE", code, DataScope.currentActor(), targetDisabled ? "DISABLE" : "ENABLE",
                "{\"roleCode\":\"" + code + "\",\"status\":\"" + r.getStatus() + "\"}");
        return r;
    }

    /** 删除自定义角色：内置角色禁删；仍有在职成员引用时禁删（先调岗/停用）。 */
    @DeleteMapping("/admin/roles/{code}")
    @RequirePerm("role:delete")
    @Transactional
    public Map<String, Object> deleteRole(@PathVariable String code) {
        if (BUILTIN_ROLES.contains(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置矩阵角色不可删除: " + code);
        }
        if (!roleRepo.existsById(code)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在: " + code);
        }
        // 主角色引用（staff.role_code）：在职成员禁删
        long primaryCount = staffRepo.findByRoleCodeOrderByStaffIdAsc(code).stream()
                .filter(s -> "在职".equals(s.getStatus())).count();
        if (primaryCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该角色仍有 " + primaryCount + " 名在职成员，请先调整其岗位后再删除");
        }
        // 清理关联（兼岗关联 + 权限关联 + 角色定义；主角色引用已在上方拦截）
        staffRoleRepo.deleteByRoleCode(code);
        rolePermRepo.deleteByRoleCode(code);
        roleRepo.deleteById(code);
        audit.record("ROLE", code, DataScope.currentActor(), "DELETE",
                "{\"roleCode\":\"" + code + "\"}");
        return Map.of("roleCode", code, "deleted", true);
    }

    /**
     * 批量覆写角色权限（全量替换语义：提交的权限码集合即角色最终权限）。
     * 内置角色禁改（矩阵为权威源，重启恢复）；通配 * 不落库（超管由代码判定）；
     * 权限码须全部存在于 permission_def。
     */
    @PutMapping("/admin/roles/{code}/permissions")
    @RequirePerm({"role:edit", "permission:edit"})
    @Transactional
    public Map<String, Object> updateRolePermissions(@PathVariable String code,
                                                     @RequestBody RolePermissionsRequest req) {
        if (BUILTIN_ROLES.contains(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置矩阵角色权限不可修改: " + code);
        }
        if (!roleRepo.existsById(code)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在: " + code);
        }
        List<String> codes = req.permissionCodes() == null ? List.of()
                : req.permissionCodes().stream().filter(Objects::nonNull).map(String::trim)
                        .filter(s -> !s.isBlank()).distinct().toList();
        if (codes.contains("*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通配权限 * 为超管专属，不允许授予自定义角色");
        }
        Set<String> valid = permRepo.findAll().stream()
                .map(PermissionDef::getPermissionCode).collect(Collectors.toSet());
        List<String> invalid = codes.stream().filter(c -> !valid.contains(c)).toList();
        if (!invalid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限码不存在: " + String.join(", ", invalid));
        }
        rolePermRepo.deleteByRoleCode(code);
        rolePermRepo.saveAll(codes.stream().map(c -> new RolePermission(code, c)).toList());
        audit.record("ROLE", code, DataScope.currentActor(), "PERM_SET",
                "{\"roleCode\":\"" + code + "\",\"permCount\":" + codes.size() + "}");
        return Map.of("roleCode", code, "permissionCount", codes.size());
    }

    // ==================== 管理页只读聚合视图 ====================

    /** 权限字典全量（权限管理页左栏/角色授权勾选源）：按资源码分组，含 resource/action/description。 */
    @GetMapping("/admin/permissions")
    @RequirePerm({"role:view", "permission:view", "rbac:view"})
    public List<PermissionDef> permissions() {
        return permRepo.findAll();
    }

    /**
     * 角色 × 权限映射（角色管理页矩阵勾选状态）：{roleCode: [permissionCode...]}，一次拉全。
     * 内置角色的权限集合同样返回（前端置灰只读）。
     */
    @GetMapping("/admin/role-permissions")
    @RequirePerm({"role:view", "permission:view", "rbac:view"})
    public Map<String, List<String>> rolePermissions() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (RolePermission rp : rolePermRepo.findAll()) {
            out.computeIfAbsent(rp.getRoleCode(), k -> new ArrayList<>()).add(rp.getPermissionCode());
        }
        return out;
    }

    /**
     * 角色 × 成员聚合（角色管理页成员抽屉）：{roleCode: [Staff...]}，员工携带主角色富化。
     * 兼岗成员与主角色成员均归入对应角色分组。
     */
    @GetMapping("/admin/role-members")
    @RequirePerm({"role:view", "rbac:view"})
    public Map<String, List<Staff>> roleMembers() {
        Map<String, RoleDef> roles = roleRepo.findAll().stream()
                .collect(Collectors.toMap(RoleDef::getRoleCode, r -> r));
        List<Staff> staffList = staffRepo.findAll(DataScope.storeSpec("storeCode"));
        Map<String, Staff> staffById = staffList.stream()
                .collect(Collectors.toMap(Staff::getStaffId, s -> s, (a, b) -> a));
        staffList.forEach(s -> s.setRole(roles.get(s.getRoleCode())));
        Map<String, List<Staff>> out = new LinkedHashMap<>();
        for (StaffRole sr : staffRoleRepo.findAll()) {
            Staff s = staffById.get(sr.getStaffId());
            if (s != null) {
                out.computeIfAbsent(sr.getRoleCode(), k -> new ArrayList<>()).add(s);
            }
        }
        return out;
    }

    /** 员工兼岗角色码列表（员工编辑页兼岗勾选）：主角色不在 staff_role 之外重复时以前端并集为准。 */
    @GetMapping("/admin/staff/{id}/roles")
    @RequirePerm({"rbac:view", "role:view"})
    public Map<String, Object> staffRoles(@PathVariable String id) {
        Staff s = staffRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        boolean visible = DataScope.canReadStore(s.getStoreCode()) || DataScope.isSelf(id);
        if (!visible) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        List<String> roles = staffRoleRepo.findByStaffId(id).stream()
                .map(StaffRole::getRoleCode).distinct().sorted().toList();
        return Map.of("staffId", id, "primaryRole", s.getRoleCode(), "roles", roles);
    }

    // ==================== 内部方法 ====================

    /** 取待管理员工：不存在/越权统一 404 中文（不泄露存在性）。 */
    private Staff getManageableStaff(String id) {
        Staff s = staffRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "员工不存在: " + id));
        if (!DataScope.canReadStore(s.getStoreCode()) && !DataScope.isSelf(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return s;
    }

    /** 角色可用性校验：停用角色不可再授予（内置角色恒启用）。 */
    private void assertRoleUsable(RoleDef role) {
        if ("停用".equals(role.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "角色已停用，不可授予员工: " + role.getRoleCode());
        }
    }

    /** 门店编码存在性校验（门店主数据在 org_unit 门店节点）。 */
    private void assertStoreExists(String storeCode) {
        boolean exists = orgRepo.findAll().stream()
                .anyMatch(u -> storeCode.equals(u.getStoreCode()));
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "门店不存在: " + storeCode);
        }
    }

    private static String jsonStr(String v) {
        return v == null ? "null" : "\"" + esc(v) + "\"";
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 请求体记录 ====================

    public record StaffCreateRequest(String staffId, String staffName, String roleCode,
                                     String storeCode, String region, Boolean medicalLicensed) {
    }

    public record StaffTransferRequest(String storeCode, String region) {
    }

    public record RoleAssignRequest(String roleCode) {
    }

    public record RoleCreateRequest(String roleCode, String roleName, String dataScope,
                                    String roleSequence, Boolean medical, String description) {
    }

    public record RoleUpdateRequest(String roleName, String dataScope, String roleSequence,
                                    Boolean medical, String description) {
    }

    public record RolePermissionsRequest(List<String> permissionCodes) {
    }
}
