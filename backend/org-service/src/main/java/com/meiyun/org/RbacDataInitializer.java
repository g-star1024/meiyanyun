package com.meiyun.org;

import com.meiyun.security.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M7 启动幂等播种器（双库 meiyun_core / meiyun_seed 均适用）：
 * 1. 角色枚举对齐：旧 8 码（ROLE_STORE_MANAGER 等）迁移为权限矩阵 8 角色（SUPER_ADMIN … FINANCE）；
 * 2. 员工角色码迁移 + 补齐集团/区域/运营/财务/前台演示员工；
 * 3. 员工登录凭证：login_name 默认=工号，password_hash 缺省置默认密码 meiyun123（PBKDF2）；
 * 4. 一人多角色：staff_role 关联表按主角色播种；
 * 5. 权限字典 permission_def + 角色权限 role_permission 由 PermissionMatrix（前端矩阵迁移）落库。
 */
@Component
public class RbacDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RbacDataInitializer.class);

    /** 种子员工默认密码（仅开发/演示；生产上线前必须强制改密）。 */
    public static final String DEFAULT_PASSWORD = "meiyun123";

    /** 旧角色码 → 矩阵 8 角色（角色枚举对齐，无 STORE_MANAGER 第九角色）。 */
    private static final Map<String, String> ROLE_MIGRATION = Map.ofEntries(
            Map.entry("ROLE_GROUP_ADMIN", "SUPER_ADMIN"),
            Map.entry("ROLE_GROUP_FIN", "FINANCE"),
            Map.entry("ROLE_GROUP_IT", "SUPER_ADMIN"),
            Map.entry("ROLE_GROUP_OPS", "OPERATOR"),
            Map.entry("ROLE_REGION", "REGION_MGR"),
            Map.entry("ROLE_AREA_MANAGER", "REGION_MGR"),
            Map.entry("ROLE_STORE_MANAGER", "STORE_MGR"),
            Map.entry("ROLE_CONSULTANT", "CONSULTANT"),
            Map.entry("ROLE_DOCTOR", "DOCTOR"),
            Map.entry("ROLE_THERAPIST", "DOCTOR"),
            Map.entry("ROLE_FRONT", "FRONT_DESK"),
            Map.entry("ROLE_CASHIER", "FRONT_DESK"));

    /** 矩阵 8 角色定义：roleCode → [名称, 数据域(中文), 岗位序列, 是否医疗岗]。 */
    private static final Map<String, String[]> CANONICAL_ROLES = new LinkedHashMap<>();

    static {
        CANONICAL_ROLES.put("SUPER_ADMIN", new String[]{"集团管理员", "集团", "01", "false", "集团级全量数据与配置权限"});
        CANONICAL_ROLES.put("REGION_MGR", new String[]{"区域经理", "区域", "10", "false", "区域内多门店数据与审批"});
        CANONICAL_ROLES.put("STORE_MGR", new String[]{"门店店长", "门店", "20", "false", "门店管理与双签终审"});
        CANONICAL_ROLES.put("CONSULTANT", new String[]{"咨询顾问", "门店", "30", "false", "客户归属、面诊与方案（SELF 数据域）"});
        CANONICAL_ROLES.put("DOCTOR", new String[]{"医生", "门店", "40", "true", "执业医师，治疗与病历"});
        CANONICAL_ROLES.put("FRONT_DESK", new String[]{"前台/收银", "门店", "60", "false", "接待登记、预约与收款双签"});
        CANONICAL_ROLES.put("OPERATOR", new String[]{"运营", "门店", "55", "false", "私域/营销/客服运营"});
        CANONICAL_ROLES.put("FINANCE", new String[]{"财务", "区域", "70", "false", "对账、退款/结算审核"});
    }

    /** 补齐的集团/职能演示员工：[工号, 姓名, 角色, 门店(可空), 医疗岗, 大区(可空)]。 */
    private static final String[][] EXTRA_STAFF = {
            {"SE101", "周岚", "SUPER_ADMIN", null, "false", null},
            {"SE102", "陈野", "REGION_MGR", null, "false", "华北"},
            {"SE103", "白桥", "OPERATOR", "ST-SH-001", "false", null},
            {"SE104", "钱进", "FINANCE", null, "false", null},
            {"SE105", "夏沫", "FRONT_DESK", "ST-SH-001", "false", null},
    };

    /**
     * 正式库（meiyun_core）联调演示员工 E001-E014：原为 db/seed_org.sql 手工 psql 灌入、
     * 无任何自动化引用，导致新环境自动部署缺失该批员工（dev-login role 模式落到 SE 系列，口径漂移）。
     * 固化为启动幂等播种后，core 新环境开箱即有这 14 人；在 seed 库（SST0x/SE001 世界）中同样
     * 按 existsById 幂等补行，不挂任何 SST 门店业务，不影响测试库基线。
     * 字段顺序同 EXTRA_STAFF：[工号, 姓名, 角色, 门店, 医疗岗(有执业资质), 大区(可空)]。
     * 角色码为迁移后矩阵 8 角色（治疗师 E001 归 DOCTOR 但 medicalLicensed=false）。
     */
    private static final String[][] CORE_SEED_STAFF = {
            {"E001", "刘治疗师", "DOCTOR", "ST-SH-001", "false", null},
            {"E002", "王前台", "FRONT_DESK", "ST-SH-001", "false", null},
            {"E003", "陈医生", "DOCTOR", "ST-SH-001", "true", null},
            {"E004", "赵咨询师", "CONSULTANT", "ST-SH-001", "false", null},
            {"E005", "李店长", "STORE_MGR", "ST-SH-001", "false", null},
            {"E006", "周收银", "FRONT_DESK", "ST-SH-001", "false", null},
            {"E007", "钱店长", "STORE_MGR", "ST-SH-002", "false", null},
            {"E008", "孙医生", "DOCTOR", "ST-SH-002", "true", null},
            {"E009", "吴店长", "STORE_MGR", "ST-BJ-001", "false", null},
            {"E010", "郑医生", "DOCTOR", "ST-BJ-001", "true", null},
            {"E011", "冯区域", "REGION_MGR", null, "false", "华东"},
            {"E012", "褚财务总监", "FINANCE", null, "false", null},
            {"E013", "卫运营", "OPERATOR", null, "false", null},
            {"E014", "蒋IT", "SUPER_ADMIN", null, "false", null},
    };

    /** 区域经理演示口径大区（E011 冯区域=华东；SE102 陈野由 EXTRA_STAFF 播种华北）。 */
    private static final Map<String, String> STAFF_REGION = Map.of("E011", "华东");

    private final RoleDefRepository roleRepo;
    private final StaffRepository staffRepo;
    private final StaffRoleRepository staffRoleRepo;
    private final PermissionDefRepository permRepo;
    private final RolePermissionRepository rolePermRepo;
    private final OrgUnitRepository orgUnitRepo;

    public RbacDataInitializer(RoleDefRepository roleRepo, StaffRepository staffRepo,
                               StaffRoleRepository staffRoleRepo, PermissionDefRepository permRepo,
                               RolePermissionRepository rolePermRepo, OrgUnitRepository orgUnitRepo) {
        this.roleRepo = roleRepo;
        this.staffRepo = staffRepo;
        this.staffRoleRepo = staffRoleRepo;
        this.permRepo = permRepo;
        this.rolePermRepo = rolePermRepo;
        this.orgUnitRepo = orgUnitRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedRoles();
        migrateStaffRoles();
        seedExtraStaff();
        seedStaffRegions();
        seedLoginCredentials();
        seedStaffRoles();
        seedPermissionDefs();
        seedRolePermissions();
        log.info("[M7] RBAC 播种完成：角色 8、权限字典 {}、员工 {}",
                PermissionMatrix.ALL_PERMISSIONS.size(), staffRepo.count());
    }

    private void seedRoles() {
        for (Map.Entry<String, String[]> e : CANONICAL_ROLES.entrySet()) {
            String code = e.getKey();
            String[] meta = e.getValue();
            RoleDef r = roleRepo.findById(code).orElseGet(RoleDef::new);
            r.setRoleCode(code);
            r.setRoleName(meta[0]);
            r.setDataScope(meta[1]);
            r.setRoleSequence(meta[2]);
            r.setMedical(Boolean.parseBoolean(meta[3]));
            r.setDescription(meta[4]);
            if (r.getStatus() == null || r.getStatus().isBlank()) r.setStatus("启用");
            roleRepo.save(r);
        }
        // 清理旧角色（员工迁移完成后旧码不再被引用）
        for (String old : ROLE_MIGRATION.keySet()) {
            roleRepo.findById(old).ifPresent(roleRepo::delete);
        }
    }

    private void migrateStaffRoles() {
        List<Staff> staff = staffRepo.findAll();
        for (Staff s : staff) {
            String oldRole = s.getRoleCode();
            String target = ROLE_MIGRATION.get(oldRole);
            if (target != null) {
                s.setRoleCode(target);
                // 治疗师迁移为医生角色但不具执业资质；原医生保持资质
                if ("ROLE_THERAPIST".equals(oldRole)) {
                    s.setMedicalLicensed(false);
                }
                staffRepo.save(s);
            }
        }
    }

    private void seedExtraStaff() {
        // 本库实际存在的门店编码集合：staff.store_code 有外键 staff_store_code_fkey 指向门店主数据，
        // core 库门店为 ST-SH-001/ST-SH-002/ST-BJ-001，seed 库为 SST01-SST06。
        // 播种时门店在本库不存在则落 NULL（集团/跨库演示口径），避免外键违例导致整个播种事务回滚。
        Set<String> existingStores = new HashSet<>();
        for (OrgUnit ou : orgUnitRepo.findAll()) {
            if (ou.getStoreCode() != null && !ou.getStoreCode().isBlank()) existingStores.add(ou.getStoreCode());
        }
        // SE101-SE105 集团职能演示员工（双库通用）
        seedStaffRows(EXTRA_STAFF, existingStores);
        // E001-E014 联调演示员工：core 库幂等补齐（新环境开箱即有，门店 ST-* 存在）；
        // seed 库（SST0x 世界）门店不存在则门店置空，按集团员工口径幂等补行，不挂 SST 门店业务，不影响测试基线。
        seedStaffRows(CORE_SEED_STAFF, existingStores);
    }

    private void seedStaffRows(String[][] rows, Set<String> existingStores) {
        for (String[] row : rows) {
            if (staffRepo.existsById(row[0])) continue;
            Staff s = new Staff();
            s.setStaffId(row[0]);
            s.setStaffName(row[1]);
            s.setRoleCode(row[2]);
            String storeCode = row[3];
            s.setStoreCode(storeCode != null && existingStores.contains(storeCode) ? storeCode : null);
            s.setMedicalLicensed(Boolean.parseBoolean(row[4]));
            if (row.length > 5 && row[5] != null && !row[5].isBlank()) s.setRegion(row[5]);
            s.setStatus("在职");
            s.setCreatedAt(OffsetDateTime.now());
            staffRepo.save(s);
        }
    }

    /**
     * 数据域大区回填（幂等）：
     * ① 区域经理演示口径显式赋值（E011=华东；SE102 新建时已带华北，此处兜底）；
     * ② 门店员工按所属门店的组织节点反查大区（org_unit 门店节点 region 来自门店主数据）；
     * ③ 财务/运营等无门店、无大区的集团职能保持 NULL（登录解析为「明细全量」）。
     */
    private void seedStaffRegions() {
        Map<String, String> storeRegion = new HashMap<>();
        for (OrgUnit ou : orgUnitRepo.findAll()) {
            if (ou.getStoreCode() != null && !ou.getStoreCode().isBlank()
                    && ou.getRegion() != null && !ou.getRegion().isBlank()) {
                storeRegion.put(ou.getStoreCode(), ou.getRegion());
            }
        }
        for (Staff s : staffRepo.findAll()) {
            String explicit = STAFF_REGION.get(s.getStaffId());
            if (explicit != null) {
                if (!explicit.equals(s.getRegion())) {
                    s.setRegion(explicit);
                    staffRepo.save(s);
                }
                continue;
            }
            boolean blank = s.getRegion() == null || s.getRegion().isBlank();
            if (blank && s.getStoreCode() != null && !s.getStoreCode().isBlank()) {
                String region = storeRegion.get(s.getStoreCode());
                if (region != null) {
                    s.setRegion(region);
                    staffRepo.save(s);
                }
            }
        }
    }

    private void seedLoginCredentials() {
        String defaultHash = PasswordEncoder.hash(DEFAULT_PASSWORD);
        for (Staff s : staffRepo.findAll()) {
            boolean dirty = false;
            if (s.getLoginName() == null || s.getLoginName().isBlank()) {
                s.setLoginName(s.getStaffId());
                dirty = true;
            }
            if (s.getPasswordHash() == null || s.getPasswordHash().isBlank()) {
                s.setPasswordHash(defaultHash);
                dirty = true;
            }
            if (dirty) staffRepo.save(s);
        }
    }

    private void seedStaffRoles() {
        Set<StaffRole.Key> existing = new HashSet<>();
        staffRoleRepo.findAll().forEach(sr -> existing.add(new StaffRole.Key(sr.getStaffId(), sr.getRoleCode())));
        for (Staff s : staffRepo.findAll()) {
            StaffRole.Key key = new StaffRole.Key(s.getStaffId(), s.getRoleCode());
            if (!existing.contains(key)) {
                staffRoleRepo.save(new StaffRole(s.getStaffId(), s.getRoleCode()));
            }
        }
    }

    private void seedPermissionDefs() {
        Set<String> existing = new HashSet<>();
        permRepo.findAll().forEach(p -> existing.add(p.getPermissionCode()));
        for (String code : PermissionMatrix.ALL_PERMISSIONS) {
            if (!existing.contains(code)) {
                permRepo.save(new PermissionDef(code));
            }
        }
    }

    private void seedRolePermissions() {
        Map<String, Set<String>> matrix = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : PermissionMatrix.rolePermissions().entrySet()) {
            Set<String> perms = new HashSet<>();
            for (String perm : e.getValue()) {
                if (!"*".equals(perm)) perms.add(perm); // 超管通配由代码判定，不落库
            }
            matrix.put(e.getKey(), perms);
        }
        // 内置 8 角色以矩阵为唯一真源：既补齐缺失授权，也回收矩阵已移除的旧授权
        // （矩阵收权后若只增不删，DB 旧授权会让收权永久不生效）。
        List<RolePermission> rows = rolePermRepo.findByRoleCodeIn(List.copyOf(matrix.keySet()));
        Map<String, Set<String>> existing = new HashMap<>();
        for (RolePermission rp : rows) {
            existing.computeIfAbsent(rp.getRoleCode(), k -> new HashSet<>()).add(rp.getPermissionCode());
        }
        for (Map.Entry<String, Set<String>> e : matrix.entrySet()) {
            String role = e.getKey();
            Set<String> want = e.getValue();
            Set<String> have = existing.computeIfAbsent(role, k -> new HashSet<>());
            for (String perm : want) {
                if (!have.contains(perm)) {
                    rolePermRepo.save(new RolePermission(role, perm));
                    have.add(perm);
                }
            }
            for (String perm : List.copyOf(have)) {
                if (!want.contains(perm)) {
                    rolePermRepo.delete(new RolePermission(role, perm));
                }
            }
        }
    }
}
