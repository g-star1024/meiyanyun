package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 薪酬提成域（B9，DESIGN §5.4）：员工薪酬配置（底薪+适用规则）、提成规则维护、
 * 月度提成单试算生成与提交/审批/驳回/发放镜像、咨询页本单预估。
 *
 * <p>类级 finance:commission:view 只读；写操作 finance:commission:edit；
 * 审批/驳回/发放登记 finance:commission:approve。金额一律 Long「分」，
 * 月份契约 yyyy-MM-01。红线：提成只写 commission_record 成本镜像，
 * 绝不在 fund_entry 动账；PAID 仅镜像外部薪酬系统回传状态。
 */
@RestController
@RequestMapping("/api/finance")
@RequirePerm("finance:commission:view")
public class CommissionController {

    private final CommissionService commissionService;

    public CommissionController(CommissionService commissionService) {
        this.commissionService = commissionService;
    }

    // ==================== 员工薪酬配置（底薪 + 适用规则） ====================

    /** 当前生效薪酬配置列表（可按门店过滤，数据域按登录人门店收敛）。 */
    @GetMapping("/comp-configs")
    public List<StaffCompConfig> compConfigs(@RequestParam(required = false) String storeCode) {
        return commissionService.listCompConfigs(storeCode);
    }

    /**
     * 保存员工薪酬配置：POST /api/finance/comp-configs。
     * body：{"staffId":"SE002","staffName":"林咨询","storeCode":"SST01",
     *        "baseSalary":1000000,"commissionRuleId":"CR...","effectiveMonth":"2026-09-01"}。
     * 调薪 = 旧 ACTIVE 置 INACTIVE + 新单 ACTIVE（不追溯历史期间）。
     */
    @PostMapping("/comp-configs")
    @RequirePerm("finance:commission:edit")
    public StaffCompConfig saveCompConfig(@RequestBody Map<String, Object> body) {
        String staffId = str(body.get("staffId"));
        String staffName = str(body.get("staffName"));
        String storeCode = str(body.get("storeCode"));
        Long baseSalary = body.get("baseSalary") == null ? null
                : Long.valueOf(String.valueOf(body.get("baseSalary")));
        String ruleId = str(body.get("commissionRuleId"));
        String monthStr = str(body.get("effectiveMonth"));
        LocalDate effectiveMonth = monthStr == null || monthStr.isBlank()
                ? null : LocalDate.parse(monthStr).withDayOfMonth(1);
        return commissionService.saveCompConfig(staffId, staffName, storeCode,
                baseSalary, ruleId, effectiveMonth, SecurityContext.currentStaffId());
    }

    // ==================== 提成规则（后台可配，随时调整） ====================

    /** 提成规则列表（含停用，按创建时间倒序）。 */
    @GetMapping("/commission-rules")
    public List<CommissionRule> rules() {
        return commissionService.listRules();
    }

    /**
     * 新建提成规则：POST /api/finance/commission-rules。
     * body：{"name":"咨询师划扣阶梯","base":"WRITEOFF","role":"CONSULTANT",
     *        "tiers":[{"min":0,"rate":600,"label":"0-8万 6%"},{"min":8000000,"rate":800}]}。
     * rate 万分位（600=6%），min 为分且严格递增，首档 min 必须为 0。
     */
    @PostMapping("/commission-rules")
    @RequirePerm("finance:commission:edit")
    public CommissionRule createRule(@RequestBody Map<String, Object> body) {
        return commissionService.createRule(str(body.get("name")), str(body.get("base")),
                str(body.get("role")), parseTiers(body.get("tiers")),
                SecurityContext.currentStaffId());
    }

    /**
     * 规则改名/改阶梯/启停：POST /api/finance/commission-rules/{ruleId}。
     * body：{"name":"...","active":false,"tiers":[...]}（字段均可缺省，缺省不改）。
     * 停用/改阶梯不影响已生成提成单，不追溯历史期间。
     */
    @PostMapping("/commission-rules/{ruleId}")
    @RequirePerm("finance:commission:edit")
    public CommissionRule updateRule(@PathVariable("ruleId") String ruleId,
                                     @RequestBody Map<String, Object> body) {
        Boolean active = body.get("active") == null ? null
                : Boolean.valueOf(String.valueOf(body.get("active")));
        return commissionService.updateRule(ruleId, str(body.get("name")), active,
                parseTiers(body.get("tiers")), SecurityContext.currentStaffId());
    }

    // ==================== 月度提成单 ====================

    /**
     * 按月生成试算单（幂等）：POST /api/finance/commission/generate?period=2026-09-01。
     * 拉 txn 当月按工号聚合业绩，对有 ACTIVE 薪酬配置的员工按规则试算；
     * 同月同人已存在则重算回 DRAFT（PAID 已发放单锁定不重算）。
     */
    @PostMapping("/commission/generate")
    @RequirePerm("finance:commission:edit")
    public List<CommissionRecord> generate(@RequestParam String period) {
        return commissionService.generate(LocalDate.parse(period).withDayOfMonth(1),
                SecurityContext.currentStaffId());
    }

    /** 提成单列表：GET /api/finance/commission?period=2026-09-01&storeCode=SST01。 */
    @GetMapping("/commission")
    public List<CommissionRecord> commission(@RequestParam String period,
                                             @RequestParam(required = false) String storeCode) {
        return commissionService.listRecords(LocalDate.parse(period).withDayOfMonth(1), storeCode);
    }

    /** 提交审批（DRAFT/REJECTED → SUBMITTED）。 */
    @PostMapping("/commission/{id}/submit")
    @RequirePerm("finance:commission:edit")
    public CommissionRecord submit(@PathVariable("id") String id) {
        return commissionService.submit(id, SecurityContext.currentStaffId());
    }

    /** 审批通过（SUBMITTED → APPROVED）。 */
    @PostMapping("/commission/{id}/approve")
    @RequirePerm("finance:commission:approve")
    public CommissionRecord approve(@PathVariable("id") String id) {
        return commissionService.approve(id, SecurityContext.currentStaffId());
    }

    /** 审批驳回（SUBMITTED → REJECTED），body 可带 {"reason":"..."}。 */
    @PostMapping("/commission/{id}/reject")
    @RequirePerm("finance:commission:approve")
    public CommissionRecord reject(@PathVariable("id") String id,
                                   @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : str(body.get("reason"));
        return commissionService.reject(id, reason, SecurityContext.currentStaffId());
    }

    /** 发放登记（APPROVED → PAID）：仅镜像外部薪酬系统已发放状态，本系统不划款。 */
    @PostMapping("/commission/{id}/mark-paid")
    @RequirePerm("finance:commission:approve")
    public CommissionRecord markPaid(@PathVariable("id") String id) {
        return commissionService.markPaid(id, SecurityContext.currentStaffId());
    }

    /**
     * 本单提成预估（咨询页用）：POST /api/finance/commission/estimate?staffId=SE002&amount=369500。
     * 按员工当前生效规则试算给定金额，不落库；未配置返回 configured=false。
     * 权限 OR：财务提成查看 或 咨询业务人员（咨询师/医生无 finance 权限，方法级覆盖类级）。
     */
    @PostMapping("/commission/estimate")
    @RequirePerm({"finance:commission:view", "consult:create", "consult:edit", "consult:view"})
    public Map<String, Object> estimate(@RequestParam String staffId,
                                        @RequestParam Long amount) {
        return commissionService.estimate(staffId, amount == null ? 0L : amount);
    }

    /**
     * 薪酬表导出 CSV：GET /api/finance/export/commission.csv?period=2026-09-01&storeCode=SST01。
     * UTF-8 BOM + 中文表头 + 金额「元」，数据域与 GET /commission 同源。
     */
    @GetMapping("/export/commission.csv")
    public ResponseEntity<byte[]> exportCommission(@RequestParam String period,
                                                   @RequestParam(required = false) String storeCode) {
        List<CommissionRecord> rows = commissionService.listRecords(
                LocalDate.parse(period).withDayOfMonth(1), storeCode);
        StringBuilder sb = new StringBuilder();
        sb.append("提成单号,月份,工号,姓名,门店,规则,业绩基数(元),单数,提成(元),状态\n");
        for (CommissionRecord r : rows) {
            sb.append(csv(r.getRecordId())).append(',')
              .append(r.getPeriod() == null ? "" : r.getPeriod().toString()).append(',')
              .append(csv(r.getStaffId())).append(',')
              .append(csv(r.getStaffName())).append(',')
              .append(csv(r.getStoreCode())).append(',')
              .append(csv(r.getRuleName())).append(',')
              .append(yuan(r.getBaseAmount())).append(',')
              .append(r.getOrderCount() == null ? 0 : r.getOrderCount()).append(',')
              .append(yuan(r.getCommission())).append(',')
              .append(csv(CommissionService.statusLabel(r.getStatus()))).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[3 + body.length];
        content[0] = (byte) 0xEF;
        content[1] = (byte) 0xBB;
        content[2] = (byte) 0xBF;
        System.arraycopy(body, 0, content, 3, body.length);
        String filename = "commission-" + period.substring(0, 7) + ".csv";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"commission.csv\"; filename*=UTF-8''" + encoded)
                .contentLength(content.length)
                .body(content);
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private List<CommissionService.Tier> parseTiers(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<CommissionService.Tier> tiers = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Long min = m.get("min") == null ? null : Long.valueOf(String.valueOf(m.get("min")));
            Integer rate = m.get("rate") == null ? null : Integer.valueOf(String.valueOf(m.get("rate")));
            String label = m.get("label") == null ? null : String.valueOf(m.get("label"));
            tiers.add(new CommissionService.Tier(min, rate, label));
        }
        return tiers;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 分 → 元（保留两位小数字符串）。 */
    private static String yuan(Long fen) {
        if (fen == null) return "0.00";
        return String.format("%.2f", fen / 100.0);
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
