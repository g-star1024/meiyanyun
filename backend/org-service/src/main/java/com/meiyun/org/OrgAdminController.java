package com.meiyun.org;

import com.meiyun.org.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * 组织树写端点（B33 建立；B87 卡2 L37 层级写放开）。
 *
 * <p>L37 放开后的写边界（父类型链硬校验，DESIGN-P5-B87 §6）：
 * 区域→父必须=集团；门店→父必须=区域且必须挂已有 store.store_code（D4，不新建 store 主数据），
 * region 继承父区域；部门→父必须=门店（B33 现状）；集团→409「集团节点唯一」（D6 禁建）。
 * 门店支持跨区移动（update parentCode 改挂其他区域），连带更新本节点 region；
 * 不级联改 staff.region/store_code，响应带 affectedStaffCount 提示人工核对。
 * 删除走 L38 物理删除（org_code_tombstone 整行快照留痕，编码禁复用 D5；集团禁删 D6）；
 * 新建撞 tombstone 编码 409「该编码已于 xx 删除回收，不可复用」。编码/类型不可改。
 *
 * <p>权限：统一持 org:edit（超管/区域经理/店长，财务无此权）。数据域：
 * 集团节点仅超管/集团域；区域节点仅集团域；门店/部门按门店数据域（STORE 本店、REGION 本区、
 * GROUP 全量），越权统一 404「数据不存在或无权查看」不泄露存在性。
 *
 * <p>审计：bizType=ORG，动作 CREATE/UPDATE/ENABLE/DISABLE/DELETE，payload 全动作合法 JSON。
 */
@RestController
@RequestMapping("/api/org")
public class OrgAdminController {

    private final OrgUnitRepository orgRepo;
    private final AuditRecorder audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final StaffRepository staffRepo;

    public OrgAdminController(OrgUnitRepository orgRepo, AuditRecorder audit,
                              org.springframework.jdbc.core.JdbcTemplate jdbc, StaffRepository staffRepo) {
        this.orgRepo = orgRepo;
        this.audit = audit;
        this.jdbc = jdbc;
        this.staffRepo = staffRepo;
    }

    /**
     * 新建节点（L37）：orgType 缺省「部门」兼容旧调用；区域→父必须集团；门店→父必须区域且
     * storeCode 必须挂已有 store 主数据（D4）且未被其他门店节点占用，region 继承父区域；
     * 部门→父必须门店（B33 现状）；集团→409（D6 全库唯一禁建）。
     */
    @PostMapping("/admin/org-units")
    @RequirePerm("org:edit")
    @Transactional
    public OrgUnit create(@RequestBody OrgUnitCreateRequest req) {
        if (req == null || req.orgCode() == null || req.orgCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织编码不能为空");
        }
        String code = req.orgCode().trim().toUpperCase();
        if (!code.matches("[A-Z0-9][A-Z0-9-_]{0,15}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "组织编码需为 1-16 位大写字母/数字/中划线/下划线，且以字母或数字开头");
        }
        if (orgRepo.existsById(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织编码已存在: " + code);
        }
        // L38/D5：编码删除回收留痕——撞 tombstone 一律 409 禁复用（回收池管理留 Backlog）。
        String tombAt = jdbc.query(
                "SELECT to_char(deleted_at, 'YYYY-MM-DD HH24:MI') FROM org_code_tombstone WHERE org_code = ?",
                rs -> rs.next() ? rs.getString(1) : null, code);
        if (tombAt != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该编码已于 " + tombAt + " 删除回收，不可复用");
        }
        if (req.orgName() == null || req.orgName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织名称不能为空");
        }
        String name = req.orgName().trim();
        if (name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织名称最长 64 字");
        }
        String orgType = (req.orgType() == null || req.orgType().isBlank()) ? "部门" : req.orgType().trim();
        if ("集团".equals(orgType)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "集团节点唯一，禁止新建");
        }
        if (!"区域".equals(orgType) && !"门店".equals(orgType) && !"部门".equals(orgType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织类型仅支持：区域/门店/部门");
        }
        if (req.parentCode() == null || req.parentCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级节点不能为空");
        }
        OrgUnit parent = orgRepo.findById(req.parentCode().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));

        OrgUnit u = new OrgUnit();
        u.setOrgCode(code);
        u.setOrgName(name);
        u.setOrgType(orgType);
        u.setParentCode(parent.getOrgCode());
        switch (orgType) {
            case "区域" -> {
                if (!"集团".equals(parent.getOrgType())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "区域上级必须是集团");
                }
                if (req.storeCode() != null && !req.storeCode().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅门店节点可挂 store");
                }
                u.setStoreCode(null);
                u.setRegion(null);
            }
            case "门店" -> {
                if (!"区域".equals(parent.getOrgType())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "门店上级必须是区域");
                }
                if (req.storeCode() == null || req.storeCode().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "门店节点必须挂已有 store 编码");
                }
                String sc = req.storeCode().trim();
                Integer storeCnt = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM store WHERE store_code = ?", Integer.class, sc);
                if (storeCnt == null || storeCnt == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "store 主数据不存在: " + sc + "（门店主数据由开店流程独立治理）");
                }
                if (orgRepo.findFirstByStoreCode(sc).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "store 已被其他门店节点占用: " + sc);
                }
                u.setStoreCode(sc);
                u.setRegion(parent.getRegion());
            }
            default -> {
                if (!"门店".equals(parent.getOrgType())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门上级必须是门店");
                }
                u.setStoreCode(parent.getStoreCode());
                u.setRegion(parent.getRegion());
            }
        }
        assertManageable(parent);
        Integer headcount = normalizeHeadcount(req.headcount());
        Integer sortNo = req.sortNo() == null ? 0 : req.sortNo();
        String leader = trimToNull(req.leaderName());
        String remark = trimToNull(req.remark());
        if (leader != null && leader.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人最长 32 字");
        }
        if (remark != null && remark.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注最长 255 字");
        }

        u.setSortNo(sortNo);
        u.setLeaderName(leader);
        u.setHeadcount(headcount);
        u.setStatus("启用");
        u.setInactiveReason(null);
        u.setRemark(remark);
        u.setCreatedAt(OffsetDateTime.now());
        orgRepo.save(u);
        audit.record("ORG", code, DataScope.currentActor(), "CREATE",
                "{\"orgCode\":\"" + code + "\",\"orgName\":\"" + esc(name)
                        + "\",\"orgType\":\"" + orgType + "\",\"parentCode\":\"" + parent.getOrgCode()
                        + "\",\"storeCode\":" + jsonStr(u.getStoreCode())
                        + ",\"region\":" + jsonStr(u.getRegion()) + "}");
        return u;
    }

    /**
     * 编辑节点：名称/负责人/编制/排序/备注；编码、类型不可改。
     * 部门支持调整上级（parentCode 改挂其他门店），门店归属与区域随新父继承；
     * 门店支持跨区移动（L37：parentCode 改挂其他区域），连带更新本节点 region，
     * 不级联改 staff.region/store_code，响应带 affectedStaffCount 提示「该店 N 名员工
     * region 未随动，请至员工管理核对」；集团/区域两级不支持移动（传 parentCode 且与现值不同即 400）。
     */
    @PutMapping("/admin/org-units/{code}")
    @RequirePerm("org:edit")
    @Transactional
    public OrgUnit update(@PathVariable String code, @RequestBody OrgUnitUpdateRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        OrgUnit u = orgRepo.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        assertManageable(u);
        if (req.orgName() != null) {
            String name = req.orgName().trim();
            if (name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织名称不能为空");
            }
            if (name.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织名称最长 64 字");
            }
            u.setOrgName(name);
        }
        // 可空文本字段：null（字段缺省）保持原值，空串显式清空，非空更新；长度先校验。
        if (req.leaderName() != null) {
            String leader = req.leaderName().trim();
            if (leader.length() > 32) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人最长 32 字");
            }
            u.setLeaderName(leader.isEmpty() ? null : leader);
        }
        if (req.headcount() != null) {
            u.setHeadcount(normalizeHeadcount(req.headcount()));
        }
        if (req.sortNo() != null) {
            u.setSortNo(req.sortNo());
        }
        if (req.remark() != null) {
            String remark = req.remark().trim();
            if (remark.length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注最长 255 字");
            }
            u.setRemark(remark.isEmpty() ? null : remark);
        }

        String fromStore = u.getStoreCode();
        String movedToStore = null;
        String fromRegion = null;
        String movedToRegion = null;
        if (req.parentCode() != null && !req.parentCode().isBlank()) {
            String targetParent = req.parentCode().trim();
            if (!"部门".equals(u.getOrgType()) && !"门店".equals(u.getOrgType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅部门/门店支持调整上级");
            }
            if (!targetParent.equals(u.getParentCode())) {
                OrgUnit np = orgRepo.findById(targetParent)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
                if ("部门".equals(u.getOrgType())) {
                    if (!"门店".equals(np.getOrgType())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门上级必须是门店");
                    }
                    assertManageable(np);
                    u.setParentCode(np.getOrgCode());
                    u.setStoreCode(np.getStoreCode());
                    u.setRegion(np.getRegion());
                    movedToStore = np.getStoreCode();
                } else {
                    // L37 门店跨区移动：新父必须区域；连带更新本节点 region；store_code 不变（D4）；
                    // 不级联改 staff.region/store_code，统计该店员工数随响应提示人工核对。
                    if (!"区域".equals(np.getOrgType())) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "门店上级必须是区域");
                    }
                    assertManageable(np);
                    fromRegion = u.getRegion();
                    u.setParentCode(np.getOrgCode());
                    u.setRegion(np.getRegion());
                    movedToRegion = np.getRegion();
                    if (u.getStoreCode() != null) {
                        u.setAffectedStaffCount(
                                staffRepo.findByStoreCodeOrderByStaffIdAsc(u.getStoreCode()).size());
                    }
                }
            }
        }
        orgRepo.save(u);
        audit.record("ORG", code, DataScope.currentActor(), "UPDATE",
                "{\"orgCode\":\"" + code + "\",\"orgName\":\"" + esc(u.getOrgName())
                        + "\",\"orgType\":\"" + u.getOrgType() + "\""
                        + (movedToStore != null
                                ? ",\"fromStore\":" + jsonStr(fromStore) + ",\"toStore\":" + jsonStr(movedToStore)
                                : "")
                        + (movedToRegion != null
                                ? ",\"fromRegion\":" + jsonStr(fromRegion) + ",\"toRegion\":" + jsonStr(movedToRegion)
                                        + ",\"affectedStaffCount\":" + u.getAffectedStaffCount()
                                : "")
                        + "}");
        return u;
    }

    /**
     * 启用/停用节点：停用必须填写原因（留痕），启用清空停用原因；已是目标状态幂等返回不重复审计。
     * 权限按节点类型走 {@link #assertManageable}（集团仅超管/集团域、区域仅集团域、门店/部门按数据域）。
     */
    @PostMapping("/admin/org-units/{code}/toggle-status")
    @RequirePerm("org:edit")
    @Transactional
    public OrgUnit toggleStatus(@PathVariable String code, @RequestBody(required = false) ToggleStatusRequest req) {
        OrgUnit u = orgRepo.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        assertManageable(u);
        boolean enable = req == null || req.enable() == null || req.enable();
        boolean currentlyActive = !"停用".equals(u.getStatus());
        if (enable) {
            if (currentlyActive) {
                return u;
            }
            u.setStatus("启用");
            u.setInactiveReason(null);
            orgRepo.save(u);
            audit.record("ORG", code, DataScope.currentActor(), "ENABLE",
                    "{\"orgCode\":\"" + code + "\",\"orgName\":\"" + esc(u.getOrgName()) + "\"}");
            return u;
        }
        String reason = req == null ? null : trimToNull(req.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "停用原因不能为空");
        }
        if (reason.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "停用原因最长 255 字");
        }
        if (!currentlyActive) {
            return u;
        }
        u.setStatus("停用");
        u.setInactiveReason(reason);
        orgRepo.save(u);
        audit.record("ORG", code, DataScope.currentActor(), "DISABLE",
                "{\"orgCode\":\"" + code + "\",\"orgName\":\"" + esc(u.getOrgName())
                        + "\",\"reason\":\"" + esc(reason) + "\"}");
        return u;
    }

    /**
     * 物理删除节点（L38）：集团禁删（D6）；存在下级节点或门店仍有在职员工时 409 拦截（不级联改 staff，
     * 不删 store 主数据）；已停用节点可直接删（inactive_reason 随快照留痕）。成功 204。
     * 删除三动作单事务——整行 jsonb 快照写 org_code_tombstone（D5 编码留痕禁复用）→ DELETE 行 →
     * 审计 DELETE（payload 同快照）。权限同写操作走 {@link #assertManageable}。
     */
    @DeleteMapping("/admin/org-units/{code}")
    @RequirePerm("org:edit")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        OrgUnit u = orgRepo.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        assertManageable(u);
        if ("集团".equals(u.getOrgType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "集团节点不可删除");
        }
        Integer children = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_unit WHERE parent_code = ?", Integer.class, code);
        if (children != null && children > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "存在 " + children + " 个下级节点，请先处理");
        }
        if ("门店".equals(u.getOrgType()) && u.getStoreCode() != null) {
            Integer activeStaff = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM staff WHERE store_code = ? AND status = '在职'",
                    Integer.class, u.getStoreCode());
            if (activeStaff != null && activeStaff > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "该门店仍有 " + activeStaff + " 名在职员工，请先调整归属");
            }
        }
        String actor = DataScope.currentActor();
        String snapshot = jdbc.query(
                "SELECT row_to_json(o)::text FROM org_unit o WHERE o.org_code = ?",
                rs -> rs.next() ? rs.getString(1) : null, code);
        jdbc.update("INSERT INTO org_code_tombstone(org_code, org_name, org_type, deleted_by, snapshot)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb)",
                code, u.getOrgName(), u.getOrgType(), actor, snapshot);
        orgRepo.delete(u);
        audit.record("ORG", code, actor, "DELETE", snapshot);
    }

    // ==================== 内部方法 ====================

    /**
     * 写权限断言：集团节点仅超管/集团域；区域节点仅集团域（区域经理不可停用/编辑整个大区）；
     * 门店/部门按门店数据域（部门沿祖先链解析所属门店）。越权统一 404 不泄露存在性。
     */
    private void assertManageable(OrgUnit u) {
        LoginUser user = DataScope.current();
        switch (u.getOrgType()) {
            case "集团", "区域" -> {
                boolean groupLevel = user != null
                        && (user.isSuper() || DataScope.SCOPE_GROUP.equals(user.scope()));
                if (!groupLevel) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
                }
            }
            default -> {
                if (!DataScope.canReadStore(resolveStoreCode(u))) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
                }
            }
        }
    }

    /** 部门归属门店：优先取自身 store_code，缺失时沿父链上溯到门店节点。 */
    private String resolveStoreCode(OrgUnit u) {
        if (u.getStoreCode() != null) {
            return u.getStoreCode();
        }
        OrgUnit cur = u;
        int guard = 0;
        while (cur.getParentCode() != null && guard++ < 16) {
            OrgUnit p = orgRepo.findById(cur.getParentCode()).orElse(null);
            if (p == null) {
                return null;
            }
            if ("门店".equals(p.getOrgType())) {
                return p.getStoreCode();
            }
            cur = p;
        }
        return null;
    }

    private Integer normalizeHeadcount(Integer hc) {
        if (hc == null) {
            return 0;
        }
        if (hc < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "编制人数不能为负数");
        }
        return hc;
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String jsonStr(String v) {
        return v == null ? "null" : "\"" + esc(v) + "\"";
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ==================== 请求体记录 ====================

    /** L37：orgType 中文枚举（区域|门店|部门），缺省「部门」兼容旧调用；storeCode 仅门店必填。 */
    public record OrgUnitCreateRequest(String orgCode, String orgName, String parentCode,
                                       String leaderName, Integer headcount, Integer sortNo,
                                       String remark, String orgType, String storeCode) {
    }

    public record OrgUnitUpdateRequest(String orgName, String parentCode, String leaderName,
                                       Integer headcount, Integer sortNo, String remark) {
    }

    public record ToggleStatusRequest(Boolean enable, String reason) {
    }
}
