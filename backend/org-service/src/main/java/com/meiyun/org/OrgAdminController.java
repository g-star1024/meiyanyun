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
 * 组织树写端点（B33）：部门新建、节点编辑/部门跨门店移动、节点启停。
 *
 * <p>刻意收窄的写边界（删除节点、新建区域/门店/集团、改类型均不支持，入 Backlog）：
 * 集团/区域/门店三级主数据由种子与门店主数据治理；本端点只允许在门店下挂第四级「部门」，
 * 避免与 store-service 门店主数据双写不一致。
 *
 * <p>权限：统一持 org:edit（超管/区域经理/店长，财务无此权）。数据域：
 * 集团节点仅超管/集团域；区域节点仅集团域；门店/部门按门店数据域（STORE 本店、REGION 本区、
 * GROUP 全量），越权统一 404「数据不存在或无权查看」不泄露存在性。
 *
 * <p>审计：bizType=ORG，动作 CREATE/UPDATE/ENABLE/DISABLE，payload 全动作合法 JSON。
 */
@RestController
@RequestMapping("/api/org")
public class OrgAdminController {

    private final OrgUnitRepository orgRepo;
    private final AuditRecorder audit;

    public OrgAdminController(OrgUnitRepository orgRepo, AuditRecorder audit) {
        this.orgRepo = orgRepo;
        this.audit = audit;
    }

    /** 新建部门：仅允许第四级「部门」，父节点必须是门店；门店归属/区域继承父门店。 */
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
        if (req.orgName() == null || req.orgName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织名称不能为空");
        }
        String name = req.orgName().trim();
        if (name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组织名称最长 64 字");
        }
        if (req.parentCode() == null || req.parentCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级门店不能为空");
        }
        OrgUnit parent = orgRepo.findById(req.parentCode().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!"门店".equals(parent.getOrgType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持在门店下新建部门");
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

        OrgUnit u = new OrgUnit();
        u.setOrgCode(code);
        u.setOrgName(name);
        u.setOrgType("部门");
        u.setParentCode(parent.getOrgCode());
        u.setStoreCode(parent.getStoreCode());
        u.setRegion(parent.getRegion());
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
                        + "\",\"orgType\":\"部门\",\"parentCode\":\"" + parent.getOrgCode()
                        + "\",\"storeCode\":" + jsonStr(parent.getStoreCode())
                        + ",\"region\":" + jsonStr(parent.getRegion()) + "}");
        return u;
    }

    /**
     * 编辑节点：名称/负责人/编制/排序/备注；编码、类型不可改。
     * 仅部门支持调整上级（parentCode 改挂其他门店），门店归属与区域随新父继承；
     * 集团/区域/门店三级不支持移动（传 parentCode 且与现值不同即 400）。
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
        if (req.parentCode() != null && !req.parentCode().isBlank()) {
            String targetParent = req.parentCode().trim();
            if (!"部门".equals(u.getOrgType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅部门支持调整上级门店");
            }
            if (!targetParent.equals(u.getParentCode())) {
                OrgUnit np = orgRepo.findById(targetParent)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
                if (!"门店".equals(np.getOrgType())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部门上级必须是门店");
                }
                assertManageable(np);
                u.setParentCode(np.getOrgCode());
                u.setStoreCode(np.getStoreCode());
                u.setRegion(np.getRegion());
                movedToStore = np.getStoreCode();
            }
        }
        orgRepo.save(u);
        audit.record("ORG", code, DataScope.currentActor(), "UPDATE",
                "{\"orgCode\":\"" + code + "\",\"orgName\":\"" + esc(u.getOrgName())
                        + "\",\"orgType\":\"" + u.getOrgType() + "\""
                        + (movedToStore != null
                                ? ",\"fromStore\":" + jsonStr(fromStore) + ",\"toStore\":" + jsonStr(movedToStore)
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

    public record OrgUnitCreateRequest(String orgCode, String orgName, String parentCode,
                                       String leaderName, Integer headcount, Integer sortNo,
                                       String remark) {
    }

    public record OrgUnitUpdateRequest(String orgName, String parentCode, String leaderName,
                                       Integer headcount, Integer sortNo, String remark) {
    }

    public record ToggleStatusRequest(Boolean enable, String reason) {
    }
}
