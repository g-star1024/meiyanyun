package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 渠道账单与勾兑端点（B12 域一，挂 /api/finance）：
 * <ul>
 *   <li>POST /api/finance/channel-bills/import —— 结算单 CSV 整批导入（finance:reconcile:edit）；</li>
 *   <li>GET  /api/finance/channel-bills        —— 账单行查询（finance:view）；</li>
 *   <li>GET  /api/finance/channel-reconcile    —— 月勾兑：系统账 × 渠道账单（finance:view）。</li>
 * </ul>
 *
 * <p>类级 finance:view 只读；导入为勾兑写动作用 finance:reconcile:edit 提权。
 * 操作人工号统一由控制器取 {@link SecurityContext#currentStaffId()} 传入服务层。
 *
 * <p><b>资金红线</b>：账单导入只写勾兑台账 pay_channel_bill，绝不产生实付渠道分录。
 */
@RestController
@RequestMapping("/api/finance")
@RequirePerm("finance:view")
public class ChannelBillController {

    private final ChannelBillService billService;
    private final ChannelReconcileService reconcileService;

    public ChannelBillController(ChannelBillService billService,
                                 ChannelReconcileService reconcileService) {
        this.billService = billService;
        this.reconcileService = reconcileService;
    }

    /**
     * 导入渠道结算单 CSV：POST /api/finance/channel-bills/import。
     * body：{"channel":"wxpay","storeCode":"SST01","importBatch":"","csv":"渠道,门店,订单号,...\n..."}。
     * 整批校验（任一行非法 → 整批拒绝不落行，错误带物理行号中文原因）；
     * import_batch 重放整批幂等跳过；importBatch 留空由服务按 IMP+yyyyMMdd-序号 生成。
     */
    @PostMapping("/channel-bills/import")
    @RequirePerm("finance:reconcile:edit")
    public ChannelBillService.ImportResult importBills(@RequestBody Map<String, Object> body) {
        return billService.importCsv(
                str(body.get("channel")),
                str(body.get("storeCode")),
                str(body.get("importBatch")),
                str(body.get("csv")),
                SecurityContext.currentStaffId());
    }

    /**
     * 账单行查询：GET /api/finance/channel-bills?channel=wxpay&storeCode=SST01&from=2026-09-01&to=2026-09-30。
     * from/to 可选（缺省宽区间），按交易时间升序；数据域按登录人门店逐行收敛。
     */
    @GetMapping("/channel-bills")
    public List<Map<String, Object>> bills(@RequestParam(required = false) String channel,
                                           @RequestParam(required = false) String storeCode,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        return billService.list(channel, storeCode, parseDay(from, true), parseDay(to, false));
    }

    /**
     * 月勾兑：GET /api/finance/channel-reconcile?month=2026-09-01&channel=wxpay&storeCode=SST01。
     * channel 必传；返回系统账/账单合计笔数、已勾兑、漏单（系统有账单无）、多单/未入账、
     * 金额不符清单、手续费行汇总与中文结论（账单未导入时诚实降级不下账实结论）。
     */
    @GetMapping("/channel-reconcile")
    public Map<String, Object> reconcile(@RequestParam String month,
                                         @RequestParam String channel,
                                         @RequestParam(required = false) String storeCode) {
        return reconcileService.reconcile(month, channel, storeCode);
    }

    // ==================== 工具 ====================

    /** yyyy-MM-dd → UTC 半开边界：start=true 取当日 00:00Z，start=false 取次日 00:00Z。 */
    private static OffsetDateTime parseDay(String day, boolean start) {
        if (day == null || day.isBlank()) {
            return start ? LocalDate.of(2000, 1, 1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime()
                    : LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        }
        LocalDate d = LocalDate.parse(day.trim());
        return (start ? d : d.plusDays(1)).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
