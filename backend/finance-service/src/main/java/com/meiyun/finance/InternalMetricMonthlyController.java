package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T2 事实表月度结算<b>内部触发</b>端点（P5-B97，DESIGN-T2 §3 D5，服务间 X-Internal-Token 系统身份）。
 *
 * <p>仿 {@link InternalPrepayMonitorController}：仅持 {@code internal:metric-monthly-run} 内部码
 * （不进 SQL 播种，由 X-Internal-Token 在鉴权拦截器映射系统/通配身份放行），普通 JWT 登录人无此码 → 403；
 * 网关层 {@code /internal/**} 对外裸 404，仅 compose 内服务间直连 finance 端口（8087/18087）可达。
 * 手动触发单月结算（运维补跑/验收对账），回显写入行数。
 */
@RestController
@RequestMapping("/api/finance/internal/metric-monthly")
@RequirePerm("internal:metric-monthly-run")
public class InternalMetricMonthlyController {

    private final MetricMonthlyService service;

    public InternalMetricMonthlyController(MetricMonthlyService service) {
        this.service = service;
    }

    /** 手动触发单月结算：POST /api/finance/internal/metric-monthly/run?month=2026-08（缺省当月）。 */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam(required = false) String month) {
        LocalDate period = resolveMonth(month);
        int rows = service.settleMonth(period);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("month", period.toString().substring(0, 7));
        resp.put("rows", rows);
        return resp;
    }

    private LocalDate resolveMonth(String month) {
        if (month == null || month.isBlank()) {
            return LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1);
        }
        String ym = month.trim().substring(0, Math.min(7, month.trim().length()));
        if (!ym.matches("\\d{4}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "月份参数 month 格式非法，需 yyyy-MM 或 yyyy-MM-01（如 2026-08）：" + month);
        }
        return LocalDate.parse(ym + "-01");
    }
}
