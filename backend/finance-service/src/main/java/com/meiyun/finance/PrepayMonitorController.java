package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 预收合规监控（B63 卡3 L85）面向前端的端点：监管页 overview 只读 + 监控规则 GET/POST/PUT。
 *
 * <p><b>不挂类级 {@code finance:view}</b>：与既有 {@link FinanceController}（类级只读）隔离为独立类，
 * 防止类级粗码放大；逐方法声明细码——
 * <ul>
 *   <li>{@code GET /prepay-monitor/overview}：{@code finance:prepay:view}，返回当前登录人数据域内
 *       未决（OPEN）规则事件；门店收敛在 {@link PrepayMonitorService#overview()} 内按 DataScope 处理；</li>
 *   <li>规则读 {@code finance:settings:view}、规则写 {@code finance:settings:edit}。</li>
 * </ul>
 *
 * <p>本卡规则管理仅后端端点供 curl 验证，不接管理 UI（守「无新页面」）；写接口四件套
 * （校验 / 门店域断言 / 审计 / 中文错误）收敛在 Service。
 */
@RestController
@RequestMapping("/api/finance/prepay-monitor")
public class PrepayMonitorController {

    private final PrepayMonitorService service;

    public PrepayMonitorController(PrepayMonitorService service) {
        this.service = service;
    }

    /** 监管页预警读模型：数据域内 OPEN 规则事件（前端并入既有三告警，4 KPI/5 checks 口径不变）。 */
    @GetMapping("/overview")
    @RequirePerm("finance:prepay:view")
    public Map<String, Object> overview() {
        return service.overview();
    }

    /** 规则列表：全局规则对认证用户可见，门店规则按数据域逐行收敛。 */
    @GetMapping("/rules")
    @RequirePerm("finance:settings:view")
    public List<Map<String, Object>> listRules() {
        return service.listRules();
    }

    /** 规则详情：不存在 / 越权统一 404。 */
    @GetMapping("/rules/{code}")
    @RequirePerm("finance:settings:view")
    public Map<String, Object> getRule(@PathVariable String code) {
        return service.getRule(code);
    }

    /** 新建规则：code 重复 409，入参非法 422，门店不在数据域 404。 */
    @PostMapping("/rules")
    @RequirePerm("finance:settings:edit")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> body) {
        return service.createRule(body, SecurityContext.currentStaffId());
    }

    /** 更新规则：code/type 不可变（不一致 422），其余字段全量提交。 */
    @PutMapping("/rules/{code}")
    @RequirePerm("finance:settings:edit")
    public Map<String, Object> updateRule(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return service.updateRule(code, body, SecurityContext.currentStaffId());
    }
}
