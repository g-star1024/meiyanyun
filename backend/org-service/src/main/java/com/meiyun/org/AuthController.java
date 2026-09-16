package com.meiyun.org;

import com.meiyun.org.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.JwtTokenUtil;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import com.meiyun.security.SecurityProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证与权限端点（M7）：
 * - POST /api/org/auth/login     工号 + 密码登录，签发自包含 JWT
 * - POST /api/org/auth/dev-login 开发期免密登录（?as= 联调工作流；meiyun.security.dev-login 开关）
 * - GET  /api/org/auth/permissions 权限字典 + 角色权限矩阵（前端启动拉取，后端为唯一真源）
 * - POST /api/org/auth/impersonate      超管代操作：签发 30 分钟短 token（sub=目标人，realSub=超管，act=目标人）
 * - POST /api/org/auth/impersonate/exit 退出代操作：凭短 token 反解 realSub 重签超管原会话
 *
 * JWT claims：sub(工号)/name/roles[]/store/scope/perms[](超管为 *)；
 * 代操作短 token 追加 realSub(真实超管)/act(被切换人工号)，权限与数据域按 sub 目标身份自然收窄。
 */
@RestController
@RequestMapping("/api/org/auth")
public class AuthController {

    /** 角色 → 数据域（SELF/STORE/BRAND/REGION/GROUP），多角色取最大。 */
    private static final Map<String, String> ROLE_SCOPE = Map.of(
            "SUPER_ADMIN", "GROUP",
            "REGION_MGR", "REGION",
            "STORE_MGR", "STORE",
            "CONSULTANT", "SELF",
            "DOCTOR", "STORE",
            "FRONT_DESK", "STORE",
            "OPERATOR", "STORE",
            "FINANCE", "REGION");

    private static final int SCOPE_RANK_SELF = 0, SCOPE_RANK_STORE = 1,
            SCOPE_RANK_BRAND = 2, SCOPE_RANK_REGION = 3, SCOPE_RANK_GROUP = 4;

    private final StaffRepository staffRepo;
    private final StaffRoleRepository staffRoleRepo;
    private final RoleDefRepository roleRepo;
    private final RolePermissionRepository rolePermRepo;
    private final PermissionDefRepository permRepo;
    private final OrgUnitRepository orgUnitRepo;
    private final JwtTokenUtil jwt;
    private final SecurityProperties securityProps;
    private final AuditRecorder audit;

    public AuthController(StaffRepository staffRepo, StaffRoleRepository staffRoleRepo,
                          RoleDefRepository roleRepo, RolePermissionRepository rolePermRepo,
                          PermissionDefRepository permRepo, OrgUnitRepository orgUnitRepo,
                          JwtTokenUtil jwt, SecurityProperties securityProps, AuditRecorder audit) {
        this.staffRepo = staffRepo;
        this.staffRoleRepo = staffRoleRepo;
        this.roleRepo = roleRepo;
        this.rolePermRepo = rolePermRepo;
        this.permRepo = permRepo;
        this.orgUnitRepo = orgUnitRepo;
        this.jwt = jwt;
        this.securityProps = securityProps;
        this.audit = audit;
    }

    // ==================== 登录 ====================

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String loginName = body.getOrDefault("loginName", "").trim();
        String password = body.getOrDefault("password", "");
        if (loginName.isEmpty() || password.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入工号与密码");
        }
        Staff staff = staffRepo.findByLoginName(loginName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "工号或密码错误"));
        if (!"在职".equals(staff.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已离职停用，请联系管理员");
        }
        if (!com.meiyun.security.PasswordEncoder.matches(password, staff.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "工号或密码错误");
        }
        return issueFor(staff, rolesOf(staff), false);
    }

    /**
     * 开发期免密登录（联调/演示）：
     * 不传参 → 按 ?as= 角色取该角色第一名员工（前端切换器用）；
     * 传 staffId → 签发该员工真实 token。生产环境 dev-login=false 时关闭。
     */
    @PostMapping("/dev-login")
    public Map<String, Object> devLogin(@RequestBody(required = false) Map<String, String> body) {
        if (!securityProps.isDevLogin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "开发期登录已关闭");
        }
        String staffId = body == null ? null : body.get("staffId");
        String asRole = body == null ? null : body.get("role");

        Staff staff;
        List<String> roles;
        if (staffId != null && !staffId.isBlank()) {
            staff = staffRepo.findById(staffId.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "员工不存在: " + staffId));
            if (!"在职".equals(staff.getStatus())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已离职停用，请联系管理员");
            }
            roles = rolesOf(staff);
        } else {
            String role = (asRole == null || asRole.isBlank()) ? "STORE_MGR" : asRole.trim().toUpperCase();
            if (!ROLE_SCOPE.containsKey(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知角色: " + role);
            }
            // 门店级角色（STORE 域）优先落到「有门店归属」的员工：
            // seed 库中 E001-E014 按集团口径幂等补播（门店置空），字典序 E<SE 会把角色登录
            // 误导到无门店员工上；故先取 store_code 非空的第一人，没有再退回第一人。
            List<Staff> roleStaff = staffRepo.findByRoleCodeOrderByStaffIdAsc(role);
            String scope = ROLE_SCOPE.get(role);
            staff = roleStaff.stream()
                    .filter(s -> "在职".equals(s.getStatus()))
                    .filter(s -> !"STORE".equals(scope)
                            || (s.getStoreCode() != null && !s.getStoreCode().isBlank()))
                    .findFirst()
                    .or(() -> roleStaff.stream().filter(s -> "在职".equals(s.getStatus())).findFirst())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色无可用员工: " + role));
            roles = List.of(role);
        }
        return issueFor(staff, roles, true);
    }

    // ==================== 权限矩阵（后端唯一真源） ====================

    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        // 矩阵是前端路由/按钮门控的真源：任何登录人都需要拉取自检，但匿名不得访问
        // （不加 @RequirePerm：一线角色不持 permission:view，门控语义应为「登录即可」而非「职能权限」）。
        if (SecurityContext.get() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> dict = permRepo.findAll().stream()
                .map(PermissionDef::getPermissionCode).sorted().toList();
        out.put("permissionDefs", dict);

        Map<String, List<String>> byRole = new LinkedHashMap<>();
        byRole.put("SUPER_ADMIN", List.of("*"));
        for (String role : ROLE_SCOPE.keySet()) {
            if ("SUPER_ADMIN".equals(role)) continue;
            byRole.put(role, new ArrayList<>());
        }
        for (RolePermission rp : rolePermRepo.findAll()) {
            byRole.computeIfAbsent(rp.getRoleCode(), k -> new ArrayList<>()).add(rp.getPermissionCode());
        }
        out.put("rolePermissions", byRole);

        List<Map<String, Object>> roles = new ArrayList<>();
        for (RoleDef r : roleRepo.findAllByOrderByRoleCodeAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roleCode", r.getRoleCode());
            m.put("roleName", r.getRoleName());
            m.put("scope", ROLE_SCOPE.getOrDefault(r.getRoleCode(), "STORE"));
            m.put("medical", r.getMedical());
            roles.add(m);
        }
        out.put("roles", roles);
        return out;
    }

    // ==================== 超管代操作（impersonate） ====================

    /**
     * 开始代操作：仅真实超管（非链式）可发起，目标必须是「在职」非超管员工。
     * 签发 30 分钟短 token：sub/roles/perms/scope/stores 全部按目标身份组装（权限与数据域自然收窄），
     * realSub=真实超管、act=目标人；不滑动续期。同请求线程经 RestAuditRecorder 写
     * COMPLIANCE/IMPERSONATE_START 审计（actor 由审计边界收敛为 realSub，payload 注入 act/realSub）。
     */
    @PostMapping("/impersonate")
    public Map<String, Object> impersonate(@RequestBody Map<String, String> body) {
        LoginUser current = SecurityContext.get();
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        if (!current.isSuper()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅集团超级管理员可发起代操作");
        }
        if (current.impersonating()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "代操作态不允许链式切换，请先退出当前代操作");
        }
        String targetStaffId = body == null ? "" : body.getOrDefault("targetStaffId", "").trim();
        String reason = body == null ? "" : body.getOrDefault("reason", "").trim();
        if (targetStaffId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写被代操作员工工号");
        }
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代操作必须填写事由");
        }
        if (targetStaffId.equals(current.staffId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能对本人发起代操作");
        }
        Staff target = staffRepo.findById(targetStaffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "目标员工不存在: " + targetStaffId));
        if (!"在职".equals(target.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "目标员工已离职停用，不可代操作");
        }
        List<String> targetRoles = rolesOf(target);
        if (targetRoles.contains("SUPER_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不允许代操作超级管理员账户");
        }

        Duration ttl = securityProps.getImpersonateTtl();
        Map<String, Object> result = issueFor(target, targetRoles, false,
                current.staffId(), target.getStaffId(), ttl);

        String payload = "{\"target\":" + jsonStr(target.getStaffId())
                + ",\"ip\":\"web\",\"detail\":"
                + jsonStr("以「" + target.getStaffName() + "」身份开始代操作，理由：" + reason)
                + ",\"risk\":\"HIGH\"}";
        audit.record("COMPLIANCE", null, DataScope.currentActor(), "IMPERSONATE_START", payload);
        return result;
    }

    /**
     * 退出代操作：仅代操作态短 token 可调。凭 realSub 反查真实超管并按其原始角色重签常规 TTL token
     * （纯短 TTL 还原，不维护服务端会话）；写 COMPLIANCE/IMPERSONATE_END 审计，会话时长取短 token iat。
     * 前端在发起前应已快照原 token，本端点为服务端权威还原通道，短 token 过期则直接 401 由前端回退快照。
     */
    @PostMapping("/impersonate/exit")
    public Map<String, Object> exitImpersonate() {
        LoginUser current = SecurityContext.get();
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        if (!current.impersonating()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前不处于代操作态");
        }
        String realSub = current.realSub();
        Staff real = staffRepo.findById(realSub)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "真实账户不存在，请重新登录"));
        if (!"在职".equals(real.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "真实账户已离职停用，请联系管理员");
        }
        List<String> realRoles = rolesOf(real);
        if (!realRoles.contains("SUPER_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "真实账户超管权限已变更，拒绝自动还原");
        }

        long minutes = 0L;
        if (current.issuedAt() != null) {
            minutes = Math.max(0L,
                    Duration.between(Instant.ofEpochSecond(current.issuedAt()), Instant.now()).toMinutes());
        }
        String payload = "{\"target\":" + jsonStr(current.act())
                + ",\"ip\":\"web\",\"detail\":"
                + jsonStr("结束代操作，会话时长 " + minutes + " 分钟")
                + ",\"risk\":\"MEDIUM\"}";
        audit.record("COMPLIANCE", null, DataScope.currentRealActor(), "IMPERSONATE_END", payload);

        return issueFor(real, realRoles, false, null, null, securityProps.getTtl());
    }

    // ==================== 内部方法 ====================

    private static String jsonStr(String v) {
        return v == null ? "null" : "\"" + esc(v) + "\"";
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<String> rolesOf(Staff staff) {
        List<String> roles = staffRoleRepo.findByStaffId(staff.getStaffId()).stream()
                .map(StaffRole::getRoleCode)
                .filter(ROLE_SCOPE::containsKey)
                .distinct().toList();
        if (roles.isEmpty()) {
            roles = List.of(staff.getRoleCode());
        }
        return roles;
    }

    private Map<String, Object> issueFor(Staff staff, List<String> roles, boolean devLogin) {
        return issueFor(staff, roles, devLogin, null, null, securityProps.getTtl());
    }

    /**
     * 组装身份（perms/scope/region/stores 与登录同口径）并签发 token。
     * realSub/act 非空时为超管代操作短 token：sub=目标人（权限数据域按目标人收窄），
     * realSub=真实超管、act=目标人工号仅随审计边界使用；tokenTtl 由调用方指定（代操作 30min 不续期）。
     */
    private Map<String, Object> issueFor(Staff staff, List<String> roles, boolean devLogin,
                                         String realSub, String act, Duration tokenTtl) {
        Set<String> perms = new LinkedHashSet<>();
        boolean superAdmin = roles.contains("SUPER_ADMIN");
        if (superAdmin) {
            perms.add("*");
        } else {
            rolePermRepo.findByRoleCodeIn(roles)
                    .forEach(rp -> perms.add(rp.getPermissionCode()));
        }
        String scope = topScope(roles);
        String region = resolveRegion(staff, scope);
        List<String> stores = resolveVisibleStores(staff, scope, region);
        LoginUser user = new LoginUser(staff.getStaffId(), staff.getStaffName(),
                roles, staff.getStoreCode(), scope, new ArrayList<>(perms),
                devLogin, region, stores, realSub, act, null);
        String token = jwt.issue(user, tokenTtl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("staffId", staff.getStaffId());
        result.put("staffName", staff.getStaffName());
        result.put("roles", roles);
        result.put("roleCode", staff.getRoleCode());
        result.put("storeCode", staff.getStoreCode());
        result.put("region", region);
        result.put("stores", stores);
        result.put("scope", scope);
        result.put("permissions", new ArrayList<>(perms));
        result.put("devLogin", devLogin);
        result.put("impersonating", realSub != null && !realSub.isBlank());
        result.put("realSub", realSub);
        result.put("act", act);
        result.put("ttlSeconds", tokenTtl.toSeconds());
        return result;
    }

    /**
     * 数据域大区解析：REGION 域取员工所属大区（staff.region）；其余域返回 null。
     * region 缺失的区域角色（如未分配大区的财务）按「明细全量」处理，不在此拦截。
     */
    private String resolveRegion(Staff staff, String scope) {
        if ("REGION".equals(scope)) {
            return staff.getRegion();
        }
        return null;
    }

    /**
     * 数据域可见门店预解析（登录时一次性算好，随 JWT stores claim 下发，各服务零跨服务调用）：
     * REGION + 大区有值 → 组织树中该大区下全部门店；
     * STORE/SELF → 本门店单元素；
     * GROUP/BRAND 或 REGION 无大区 → 空列表（DataScope 遇空视为全量）。
     */
    private List<String> resolveVisibleStores(Staff staff, String scope, String region) {
        if ("REGION".equals(scope)) {
            if (region == null || region.isBlank()) {
                return List.of();
            }
            return orgUnitRepo.findByRegionOrderBySortNoAsc(region).stream()
                    .map(OrgUnit::getStoreCode)
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
        }
        if (("STORE".equals(scope) || "SELF".equals(scope)) && staff.getStoreCode() != null) {
            return List.of(staff.getStoreCode());
        }
        return List.of();
    }

    private String topScope(List<String> roles) {
        int best = -1;
        String bestScope = "SELF";
        for (String r : roles) {
            int rank = switch (ROLE_SCOPE.getOrDefault(r, "STORE")) {
                case "GROUP" -> SCOPE_RANK_GROUP;
                case "REGION" -> SCOPE_RANK_REGION;
                case "BRAND" -> SCOPE_RANK_BRAND;
                case "STORE" -> SCOPE_RANK_STORE;
                default -> SCOPE_RANK_SELF;
            };
            if (rank > best) {
                best = rank;
                bestScope = ROLE_SCOPE.get(r);
            }
        }
        return bestScope;
    }
}
