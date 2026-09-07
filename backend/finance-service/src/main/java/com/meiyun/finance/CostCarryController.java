package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 月结成本结转域（B11，DESIGN §四）：期末自动计提折旧与人工成本，规则后台可配、随时调整。
 *
 * <p>类级 finance:view 只读（财务域无独立 cost:view 权限码）；规则/资产维护与结转执行
 * 用 finance:cost:edit。金额一律 Long「分」，月份契约 yyyy-MM-01。
 *
 * <p><b>资金红线</b>：结转只写成本镜像（fund_entry TK-DEPRECIATION/TK-LABOR OUT、
 * source=SYSTEM、channel=null，钩子同写 cost_allocation），绝不生成实付渠道分录。
 */
@RestController
@RequestMapping("/api/finance")
@RequirePerm("finance:view")
public class CostCarryController {

    private final CostCarryService carryService;

    public CostCarryController(CostCarryService carryService) {
        this.carryService = carryService;
    }

    // ==================== 结转规则（后台可配，随时调整） ====================

    /** 结转规则列表（含停用，按规则号升序）。 */
    @GetMapping("/carry-rules")
    public List<CostCarryRule> rules() {
        return carryService.listRules();
    }

    /**
     * 新建结转规则：POST /api/finance/carry-rules。
     * body：{"ruleName":"设备折旧自动计提","costType":"DEPRECIATION","calcMode":"ASSET",
     *        "fixedAmount":null,"storeCode":null,"enabled":true,"runOnClose":true,"remark":"..."}。
     * storeCode 空 = 全门店通用；FIXED 固定额必须指定门店且 fixedAmount 为正数（分）。
     */
    @PostMapping("/carry-rules")
    @RequirePerm("finance:cost:edit")
    public CostCarryRule createRule(@RequestBody Map<String, Object> body) {
        return carryService.createRule(
                str(body.get("ruleName")), str(body.get("costType")), str(body.get("calcMode")),
                body.get("fixedAmount") == null ? null : Long.valueOf(String.valueOf(body.get("fixedAmount"))),
                str(body.get("storeCode")),
                body.get("enabled") == null ? null : Boolean.valueOf(String.valueOf(body.get("enabled"))),
                body.get("runOnClose") == null ? null : Boolean.valueOf(String.valueOf(body.get("runOnClose"))),
                str(body.get("remark")), SecurityContext.currentStaffId());
    }

    /**
     * 更新规则（改名/类型/取数方式/固定额/门店/启停/封账自动/备注）：POST /api/finance/carry-rules/{ruleId}。
     * body 字段缺省不改；停用不追溯已结转期间。
     */
    @PostMapping("/carry-rules/{ruleId}")
    @RequirePerm("finance:cost:edit")
    public CostCarryRule updateRule(@PathVariable("ruleId") String ruleId,
                                    @RequestBody Map<String, Object> body) {
        return carryService.updateRule(ruleId, body, SecurityContext.currentStaffId());
    }

    // ==================== 月度结转：测算 / 执行 / 未结转提示 ====================

    /**
     * 测算本月结转（dry-run，不落库）：POST /api/finance/carry/preview?month=2026-09-01。
     * 逐启用规则 × 适用门店回带金额/取数说明/是否已结转，数据域按登录人门店逐行收敛。
     */
    @PostMapping("/carry/preview")
    public Map<String, Object> preview(@RequestParam String month) {
        return carryService.preview(parseMonth(month), SecurityContext.currentStaffId());
    }

    /**
     * 执行结转：POST /api/finance/carry/run?month=2026-09-01[&recalc=true]。
     * recalc=true「重算本月」：先删该月 SYSTEM 结转行再跑（已有门店封账则 422 拒绝）；
     * 已结转幂等跳过；金额 0 跳过；闭期门店逐行跳过；仅写成本镜像不产生实付分录。
     */
    @PostMapping("/carry/run")
    @RequirePerm("finance:cost:edit")
    public Map<String, Object> run(@RequestParam String month,
                                   @RequestParam(required = false, defaultValue = "false") boolean recalc) {
        return carryService.run(parseMonth(month), recalc, SecurityContext.currentStaffId());
    }

    /**
     * 未结转提示：GET /api/finance/carry/pending?month=2026-09-01&storeCode=SST01。
     * 封账页「本月 N 项结转未执行」数据源：run_on_close 启用规则中本月未结转项数/金额。
     */
    @GetMapping("/carry/pending")
    public Map<String, Object> pending(@RequestParam String month,
                                       @RequestParam(required = false) String storeCode) {
        return carryService.pending(parseMonth(month), storeCode);
    }

    // ==================== 设备资产台账 ====================

    /** 设备资产列表（IN_USE + DISPOSED，可按门店过滤）。 */
    @GetMapping("/assets")
    public List<FinAsset> assets(@RequestParam(required = false) String storeCode) {
        return carryService.listAssets(storeCode);
    }

    /**
     * 新增设备资产：POST /api/finance/assets。
     * body：{"assetName":"皮秒激光仪","storeCode":"SST01","originalValue":50000000,
     *        "salvageRate":5,"usefulMonths":120,"startMonth":"2026-07-01"}。
     * 月折旧 = round(原值 ×(100-残值率)/100 / 月限)，起折月（含）之后每月计提。
     */
    @PostMapping("/assets")
    @RequirePerm("finance:cost:edit")
    public FinAsset createAsset(@RequestBody Map<String, Object> body) {
        return carryService.createAsset(
                str(body.get("assetName")), str(body.get("storeCode")),
                body.get("originalValue") == null ? null : Long.valueOf(String.valueOf(body.get("originalValue"))),
                body.get("salvageRate") == null ? null : Integer.valueOf(String.valueOf(body.get("salvageRate"))),
                body.get("usefulMonths") == null ? null : Integer.valueOf(String.valueOf(body.get("usefulMonths"))),
                body.get("startMonth") == null ? null : LocalDate.parse(str(body.get("startMonth"))).withDayOfMonth(1),
                SecurityContext.currentStaffId());
    }

    /** 资产处置：POST /api/finance/assets/{assetId}/dispose（IN_USE → DISPOSED，处置月起停折）。 */
    @PostMapping("/assets/{assetId}/dispose")
    @RequirePerm("finance:cost:edit")
    public FinAsset disposeAsset(@PathVariable("assetId") String assetId) {
        return carryService.disposeAsset(assetId, SecurityContext.currentStaffId());
    }

    // ==================== 工具 ====================

    private static LocalDate parseMonth(String month) {
        return LocalDate.parse(month).withDayOfMonth(1);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
