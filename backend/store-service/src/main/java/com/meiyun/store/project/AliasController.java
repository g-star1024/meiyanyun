package com.meiyun.store.project;

import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 项目别名 CRUD 端点（P5-B96 卡1，DESIGN-T2 §3 D2，网关 /api/stores 路由进 store-service）。
 *
 * <p>泛化项目名 → SKU 的映射由运营统一维护：全局别名（storeCode 空）集团级，门店级别名同 alias
 * 优先命中。查询走 {@code brand:view}，建档/更新/启停/删除走 {@code brand:edit}（复用 project 域
 * 既有权限码，零新造）。alias/storeCode 建档后不可改，换映射请删旧建新。
 */
@RestController
@RequestMapping("/api/stores/aliases")
public class AliasController {

    private final ProjectAliasService service;

    public AliasController(ProjectAliasService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("brand:view")
    public List<Map<String, Object>> listAliases(@RequestParam(value = "storeCode", required = false) String storeCode,
                                                 @RequestParam(value = "keyword", required = false) String keyword) {
        return service.listAliases(storeCode, keyword);
    }

    @PostMapping
    @RequirePerm("brand:edit")
    public Map<String, Object> createAlias(@RequestBody AliasCmd cmd) {
        if (cmd == null) throw ProjectAliasService.badReq("请求体不能为空");
        ProjectAlias a = service.createAlias(cmd.alias(), cmd.storeCode(), cmd.sku(), cmd.remark(), operator());
        return Map.of("id", a.getId(), "alias", a.getAlias(), "sku", a.getSku());
    }

    @PostMapping("/{id}")
    @RequirePerm("brand:edit")
    public Map<String, Object> updateAlias(@PathVariable("id") Long id, @RequestBody AliasUpdateCmd cmd) {
        if (cmd == null) throw ProjectAliasService.badReq("请求体不能为空");
        service.updateAlias(id, cmd.sku(), cmd.remark(), operator());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/status")
    @RequirePerm("brand:edit")
    public Map<String, Object> setAliasStatus(@PathVariable("id") Long id, @RequestBody AliasStatusCmd cmd) {
        if (cmd == null) throw ProjectAliasService.badReq("请求体不能为空");
        service.setAliasStatus(id, cmd.status(), operator());
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    @RequirePerm("brand:edit")
    public Map<String, Object> deleteAlias(@PathVariable("id") Long id) {
        service.deleteAlias(id, operator());
        return Map.of("ok", true);
    }

    private static String operator() {
        LoginUser u = SecurityContext.get();
        return u == null ? "系统" : u.staffName();
    }

    /** 别名新建入参；storeCode 空=全局别名。 */
    public record AliasCmd(String alias, String storeCode, String sku, String remark) {}

    /** 别名更新入参（null 字段不覆盖；alias/storeCode 不可改）。 */
    public record AliasUpdateCmd(String sku, String remark) {}

    /** 别名启停入参（ACTIVE/INACTIVE，幂等）。 */
    public record AliasStatusCmd(String status) {}
}
