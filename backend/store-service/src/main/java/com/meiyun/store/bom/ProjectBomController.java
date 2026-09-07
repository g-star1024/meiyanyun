package com.meiyun.store.bom;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目用料配方（BOM）公开端点（B10，网关 /api/stores 路由进 store-service）。
 *
 * <p>配方查询走 {@code inventory:consumable:view}，新增/更新/停用走 {@code inventory:consumable:edit}
 * （权限码复用耗材台账，不新增）。自动扣料不对页面开放——由 txn 划扣后以系统身份回调
 * {@link InternalBomController}。数据域：门店角色只能维护本店配方；集团模板（storeCode 空/GROUP）
 * 仅集团/品牌角色可写。
 */
@RestController
@RequestMapping("/api/stores/project-boms")
public class ProjectBomController {

    private final ProjectBomService service;

    public ProjectBomController(ProjectBomService service) {
        this.service = service;
    }

    /** 配方列表：storeCode 传 GROUP/空查集团模板，传门店码查门店配方，不传查全部可见域。 */
    @GetMapping
    @RequirePerm("inventory:consumable:view")
    public List<Map<String, Object>> list(@RequestParam(value = "projectName", required = false) String projectName,
                                          @RequestParam(value = "storeCode", required = false) String storeCode) {
        return service.listBoms(projectName, resolveReadStoreCode(storeCode));
    }

    /** 新增/更新/停用配方行（同 项目+门店+SKU 唯一，重复提交更新用量/启用态）。 */
    @PostMapping
    @RequirePerm("inventory:consumable:edit")
    public Map<String, Object> upsert(@RequestBody BomCmd cmd) {
        if (cmd == null) throw ProjectBomService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String operator = u == null ? "系统" : u.staffName();
        String storeCode = resolveWriteStoreCode(cmd.storeCode());
        ProjectBom b = service.upsert(cmd.projectName(), storeCode, cmd.skuCode(),
                cmd.qty(), cmd.enabled(), operator);
        return Map.of("bomId", b.getBomId(), "projectName", b.getProjectName(),
                "storeCode", b.getStoreCode().isBlank() ? "GROUP" : b.getStoreCode(),
                "skuCode", b.getSkuCode(), "qty", b.getQty(), "enabled", b.isEnabled());
    }

    // ---- 数据域 ----

    /** 查询：门店角色强制本店（显式传他店且不可见时回落 __NONE__）；集团/品牌可自由传参。 */
    private String resolveReadStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return requested;
        }
        if (requested != null && !requested.isBlank() && !"GROUP".equalsIgnoreCase(requested.trim())) {
            if (!DataScope.canReadStore(requested.trim())) {
                return "__NONE__";
            }
            return requested.trim();
        }
        return u.storeCode();
    }

    /** 写操作：门店角色只能写本店且不能写集团模板；集团/品牌写门店配方须显式传门店码。 */
    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        boolean groupRole = u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope());
        boolean wantGroup = requested == null || requested.isBlank() || "GROUP".equalsIgnoreCase(requested.trim());
        if (wantGroup) {
            if (!groupRole) {
                throw ProjectBomService.badReq("集团模板配方仅集团/品牌角色可维护，门店角色请配本店配方");
            }
            return ProjectBomService.GROUP_TEMPLATE;
        }
        String sc = requested.trim();
        if (!groupRole) {
            if (u.storeCode() == null || u.storeCode().isBlank()) {
                throw ProjectBomService.badReq("当前账号无所属门店，无法维护配方");
            }
            if (!sc.equals(u.storeCode())) {
                throw ProjectBomService.badReq("门店角色仅能维护本店配方");
            }
        }
        return sc;
    }

    /** 配方维护入参：storeCode 空/GROUP=集团模板；qty 正整数；enabled 缺省 true */
    public record BomCmd(String projectName, String storeCode, String skuCode,
                         Integer qty, Boolean enabled) {}
}
