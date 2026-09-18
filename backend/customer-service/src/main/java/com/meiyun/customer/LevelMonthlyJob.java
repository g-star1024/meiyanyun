package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会员等级定时批处理（域①-B62 卡1，CARD-H）。
 *
 * <p>每月 1 号 00:05（北京时区，{@code zone="Asia/Shanghai"}）触发，编排四步：
 * ①先 {@link MonthlySpendService#aggregateClosedMonths()} 保证月消费事实闭合（某月失败软降级，
 * 继续用已闭合的旧事实判定，不阻断调级）；②{@link LevelInitService#ensureInitialized()} LEVEL_INIT 补种；
 * ③读 level_rule_config 的 auto_upgrade/auto_downgrade 双开关（null/读失败默认开启），各自独立执行
 * {@link CustomerService#autoUpgrade()}（只升不降，真源=截至 M net_fen 累计）与
 * {@link CustomerService#autoDowngrade()}（保护期+连续3月未达标+只降一级）；
 * ④审计：升级 upgraded&gt;0 落 LEVEL/AUTO_UPGRADE；降级每轮必落 LEVEL/AUTO-DOWNGRADE-{M}
 * （downgraded=0 也落一条证明批处理执行，payload items 只带前 20 条防超长），
 * 降级客户逐条经 {@link LevelDowngradeNotifier} 软降级告知本店店长（公海只审计不发信）。
 * 审计 actor 统一记为 SYSTEM（定时任务无登录人）。
 */
@Component
public class LevelMonthlyJob {

    private static final Logger log = LoggerFactory.getLogger(LevelMonthlyJob.class);
    private static final String ACTOR = "SYSTEM";
    private static final int AUDIT_ITEM_LIMIT = 20;

    private final CustomerService customerService;
    private final MonthlySpendService monthlySpendService;
    private final LevelInitService levelInitService;
    private final LevelDowngradeNotifier downgradeNotifier;
    private final AuditRecorder audit;

    public LevelMonthlyJob(CustomerService customerService,
                          MonthlySpendService monthlySpendService,
                          LevelInitService levelInitService,
                          LevelDowngradeNotifier downgradeNotifier,
                          AuditRecorder audit) {
        this.customerService = customerService;
        this.monthlySpendService = monthlySpendService;
        this.levelInitService = levelInitService;
        this.downgradeNotifier = downgradeNotifier;
        this.audit = audit;
    }

    /**
     * 每月 1 号 00:05（北京时区）执行（cron：秒 分 时 日 月 周）。
     */
    @Scheduled(cron = "${meiyun.level-monthly.cron:0 5 0 1 * ?}", zone = "Asia/Shanghai")
    public void monthlyLevel() {
        try {
            MonthlySpendService.AggregateResult agg = monthlySpendService.aggregateClosedMonths();
            if (agg.error() != null && !agg.error().isBlank()) {
                log.warn("月消费事实聚合部分失败，按已闭合月旧事实继续本轮等级批处理：{}", agg.error());
            }
        } catch (Exception ex) {
            log.warn("月消费事实聚合失败（软降级，用旧事实继续）：{}", ex.getMessage());
        }
        try {
            levelInitService.ensureInitialized();
        } catch (Exception ex) {
            log.warn("LEVEL_INIT 基线补种失败（继续本轮，无锚点客户将保守不降级）：{}", ex.getMessage());
        }

        LevelRuleConfig rule = readRule();
        if (rule.getAutoUpgrade() == null || rule.getAutoUpgrade()) {
            runUpgrade();
        } else {
            log.info("等级定时批处理跳过升级：autoUpgrade 开关关闭");
        }
        if (rule.getAutoDowngrade() == null || rule.getAutoDowngrade()) {
            runDowngrade();
        } else {
            log.info("等级定时批处理跳过降级：autoDowngrade 开关关闭");
        }
    }

    private LevelRuleConfig readRule() {
        try {
            return customerService.getLevelRule();
        } catch (Exception ex) {
            log.warn("读取升降级规则开关失败，按默认双开执行：{}", ex.getMessage());
            return new LevelRuleConfig();
        }
    }

    private void runUpgrade() {
        CustomerService.AutoUpgradeResult result = customerService.autoUpgrade();
        if (result.upgraded() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"closedMonth\":\"").append(esc(result.closedMonth()))
                    .append("\",\"upgraded\":").append(result.upgraded()).append(",\"items\":[");
            for (int i = 0; i < result.items().size(); i++) {
                CustomerService.UpgradeItem it = result.items().get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"customerId\":\"").append(esc(it.customerId()))
                        .append("\",\"name\":\"").append(esc(it.name()))
                        .append("\",\"storeCode\":\"").append(esc(it.storeCode()))
                        .append("\",\"fromLevel\":\"").append(esc(it.fromLevel()))
                        .append("\",\"toLevel\":\"").append(esc(it.toLevel()))
                        .append("\",\"netSpendYuan\":").append(it.netSpendYuan()).append('}');
            }
            sb.append("]}");
            audit.record("LEVEL", "AUTO-UPGRADE", ACTOR, "AUTO_UPGRADE", sb.toString());
        }
        log.info("等级定时批处理升级完成：闭合月 {} 升级 {} 人", result.closedMonth(), result.upgraded());
    }

    private void runDowngrade() {
        CustomerService.DowngradeResult result = customerService.autoDowngrade();
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(result.items().size(), AUDIT_ITEM_LIMIT);
        sb.append("{\"runMonth\":\"").append(esc(result.runMonth()))
                .append("\",\"downgraded\":").append(result.downgraded())
                .append(",\"items\":[");
        for (int i = 0; i < shown; i++) {
            CustomerService.DowngradeItem it = result.items().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"customerId\":\"").append(esc(it.customerId()))
                    .append("\",\"name\":\"").append(esc(it.name()))
                    .append("\",\"storeCode\":\"").append(esc(it.storeCode()))
                    .append("\",\"fromLevel\":\"").append(esc(it.fromLevel()))
                    .append("\",\"toLevel\":\"").append(esc(it.toLevel()))
                    .append("\",\"netM2\":").append(it.netM2())
                    .append(",\"netM1\":").append(it.netM1())
                    .append(",\"netM\":").append(it.netM()).append('}');
        }
        sb.append("]}");
        audit.record("LEVEL", "AUTO-DOWNGRADE-" + result.runMonth(), ACTOR, "AUTO_DOWNGRADE", sb.toString());

        for (CustomerService.DowngradeItem it : result.items()) {
            downgradeNotifier.notifyOne(it, result.runMonth());
        }
        log.info("等级定时批处理降级完成：跑批月 {} 降级 {} 人", result.runMonth(), result.downgraded());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
