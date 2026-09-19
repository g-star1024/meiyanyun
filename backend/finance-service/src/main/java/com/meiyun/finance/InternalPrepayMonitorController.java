package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预收合规监控<b>内部触发</b>端点（B63 卡3 L85，服务间 X-Internal-Token 系统身份）。
 *
 * <p>与面向前端的 {@link PrepayMonitorController} 隔离为独立类：本类仅持
 * {@code internal:prepay-monitor-run} 内部码（不进 SQL 播种，由 X-Internal-Token 在鉴权拦截器
 * 映射系统/通配身份放行），普通 JWT 登录人无此码 → 403；网关层 {@code /internal/**} 对外裸 404，
 * 仅 compose 内服务间直连 finance 端口（8087/18087）可达。手动触发一轮扫描，回显 {@code ScanResult}。
 */
@RestController
@RequestMapping("/api/finance/internal/prepay-monitor")
@RequirePerm("internal:prepay-monitor-run")
public class InternalPrepayMonitorController {

    private final PrepayMonitorService service;

    public InternalPrepayMonitorController(PrepayMonitorService service) {
        this.service = service;
    }

    /** 手动触发一轮全量扫描：POST /api/finance/internal/prepay-monitor/run。 */
    @PostMapping("/run")
    public PrepayMonitorService.ScanResult run() {
        return service.runOnce();
    }
}
