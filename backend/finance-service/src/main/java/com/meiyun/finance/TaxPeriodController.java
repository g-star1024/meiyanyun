package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 增值税申报期 HTTP 端点（B63 卡4 L86，{@code /api/finance/tax-periods}）。
 *
 * <p>类级 {@code finance:view} 兜底只读；列表/当前期 finance:tax:view，
 * 懒创建 ensure finance:input:edit，申报/更正 finance:input:confirm。
 * 薄调 {@link TaxPeriodService}，快照/状态机/审计/中文错误收敛在 Service。
 */
@RestController
@RequestMapping("/api/finance/tax-periods")
@RequirePerm("finance:view")
public class TaxPeriodController {

    private final TaxPeriodService service;

    public TaxPeriodController(TaxPeriodService service) {
        this.service = service;
    }

    /** 期间分页（元）；可按 type(MONTH/QUARTER)、year 过滤。 */
    @GetMapping
    @RequirePerm("finance:tax:view")
    public Page<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return service.list(type, year, page, size);
    }

    /** 当前开放期＋五金额（OPEN 实时预览）；未登记返 exists=false 骨架，不抛 404。 */
    @GetMapping("/current")
    @RequirePerm("finance:tax:view")
    public Map<String, Object> current(@RequestParam(required = false) String type) {
        return service.current(type);
    }

    /** 幂等确保期间存在（body periodType/period 缺省当前月期）；并发唯一冲突回落重查。 */
    @PostMapping("/ensure")
    @RequirePerm("finance:input:edit")
    public Map<String, Object> ensure(@RequestBody(required = false) Map<String, Object> body) {
        return service.ensure(body, SecurityContext.currentStaffName());
    }

    /** 申报：OPEN → FILED/LATE_FILED（按截止日与服务器日期判定），五金额快照落库并锁抵扣。 */
    @PostMapping("/{id}/file")
    @RequirePerm("finance:input:confirm")
    public Map<String, Object> file(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String remark = body == null ? null : String.valueOf(body.getOrDefault("remark", ""));
        return service.file(id, remark, SecurityContext.currentStaffName());
    }

    /** 更正申报：FILED/LATE_FILED/AMENDED → AMENDED；快照原值不改，备注追加留痕。 */
    @PostMapping("/{id}/amend")
    @RequirePerm("finance:input:confirm")
    public Map<String, Object> amend(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? null : String.valueOf(body.getOrDefault("note", ""));
        return service.amend(id, note, SecurityContext.currentStaffName());
    }
}
