package com.meiyun.finance;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * finance-service（M6 数据财务）—— 全只读。
 *
 * 红线：资金只读镜像 + Outbox 对账，全站无资金动词按钮（无任何 POST/PUT/DELETE 资金动作）。
 * 恒等式（DB CHECK 约束保证）：
 *   ① 预收池 total = pending_consume + refundable + earned_pending（940万=658+188+94）
 *   ② revenue_monthly：revenue = cost + gross_profit，cost_rate + gross_rate ≈ 1
 */
@RestController
@RequestMapping("/api/finance")
@RequirePerm("finance:view")
public class FinanceController {

    private final PrepayPoolRepository poolRepo;
    private final TaxRepository taxRepo;
    private final AccountMirrorRepository acctRepo;
    private final RevenueMonthlyRepository revRepo;
    private final OutboxRepository outboxRepo;

    public FinanceController(PrepayPoolRepository poolRepo, TaxRepository taxRepo,
                             AccountMirrorRepository acctRepo, RevenueMonthlyRepository revRepo,
                             OutboxRepository outboxRepo) {
        this.poolRepo = poolRepo;
        this.taxRepo = taxRepo;
        this.acctRepo = acctRepo;
        this.revRepo = revRepo;
        this.outboxRepo = outboxRepo;
    }

    /** 预收沉淀池（940万 = 待核销 658 + 可退 188 + 待结转 94）。 */
    @GetMapping("/prepay-pool")
    public PrepayPool prepayPool() {
        return poolRepo.findById(1).orElse(new PrepayPool());
    }

    /** 税务四税种（增值/城建/教育费附加/地方教育附加）。 */
    @GetMapping("/tax")
    public List<Tax> tax() {
        return taxRepo.findAll();
    }

    /** 税务合计（全口径）。 */
    @GetMapping("/tax/total")
    public Map<String, Object> taxTotal() {
        List<Tax> all = taxRepo.findAll();
        long sum = all.stream().mapToLong(Tax::getAmount).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAmount", sum);
        m.put("catCount", all.size());
        return m;
    }

    /** 三账户只读镜像（对公活期/支付宝商户/微信商户）。 */
    @GetMapping("/accounts")
    public List<AccountMirror> accounts() {
        return acctRepo.findAll();
    }

    /** 门店月度营收（可按月份/门店过滤；数据域强制注入）。 */
    @GetMapping("/revenue")
    public List<RevenueMonthly> revenue(
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String month) {
        Specification<RevenueMonthly> spec = DataScope.storeSpec("storeCode");
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        if (month != null && !month.isBlank()) {
            LocalDate m = LocalDate.parse(month);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("periodMonth"), m));
        }
        return revRepo.findAll(spec,
                Sort.by(Sort.Order.asc("periodMonth"), Sort.Order.asc("storeCode")));
    }

    /** Outbox 对账事件队列（可按状态过滤：已投递/已对账/差异）。 */
    @GetMapping("/outbox")
    public List<OutboxRecord> outbox(@RequestParam(required = false) String status) {
        return status == null ? outboxRepo.findAll() : outboxRepo.findByStatusOrderByCreatedAtDesc(status);
    }

    /** 对账核验（只读）：统计各状态笔数与净额。 */
    @GetMapping("/outbox/reconcile")
    public Map<String, Object> reconcile() {
        List<OutboxRecord> all = outboxRepo.findAll();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long net = 0;
        for (OutboxRecord r : all) {
            byStatus.merge(r.getStatus(), 1L, Long::sum);
            net += r.getAmount();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRecords", all.size());
        m.put("byStatus", byStatus);
        m.put("netAmount", net);
        return m;
    }
}
