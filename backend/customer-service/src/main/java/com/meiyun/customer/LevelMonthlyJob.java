package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会员等级定时批处理（域①-259）。
 *
 * <p>按 {@link LevelRuleConfig#getCalcPeriod()} 默认「自然月（每月1号）」在每月 1 号 00:05 触发，
 * 调用既有 {@link CustomerService#autoUpgrade()} 做「只升不降」的全量重算（与手动触发端点同逻辑）。
 * 受 {@code autoUpgrade} 开关收敛：关闭则跳过本轮。有实际升级时落单条 LEVEL/AUTO_UPGRADE 汇总审计
 * （payload 与手动端点 {@code POST /api/customer/member-levels/auto-upgrade} 完全一致，便于统一审计口径）。
 * 审计 actor 统一记为 SYSTEM（定时任务无登录人）。
 */
@Component
public class LevelMonthlyJob {

    private static final Logger log = LoggerFactory.getLogger(LevelMonthlyJob.class);
    private static final String ACTOR = "SYSTEM";

    private final CustomerService customerService;
    private final AuditRecorder audit;

    public LevelMonthlyJob(CustomerService customerService, AuditRecorder audit) {
        this.customerService = customerService;
        this.audit = audit;
    }

    /**
     * 每月 1 号 00:05 执行（cron：秒 分 时 日 月 周）。
     * 自然月口径与 LevelRuleConfig.calcPeriod 默认文案一致；若后续支持「每季/每半年」再扩展 cron。
     */
    @Scheduled(cron = "${meiyun.level-monthly.cron:0 5 0 1 * ?}")
    public void monthlyUpgrade() {
        boolean auto = true;
        try {
            auto = customerService.getLevelRule().getAutoUpgrade() == null
                    || customerService.getLevelRule().getAutoUpgrade();
        } catch (Exception ex) {
            log.warn("读取升降级规则开关失败，按默认开启执行：{}", ex.getMessage());
        }
        if (!auto) {
            log.info("等级定时批处理跳过：autoUpgrade 开关关闭");
            return;
        }
        CustomerService.AutoUpgradeResult result = customerService.autoUpgrade();
        if (result.upgraded() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"upgraded\":").append(result.upgraded()).append(",\"items\":[");
            for (int i = 0; i < result.items().size(); i++) {
                CustomerService.UpgradeItem it = result.items().get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"customerId\":\"").append(esc(it.customerId()))
                        .append("\",\"name\":\"").append(esc(it.name()))
                        .append("\",\"fromLevel\":\"").append(esc(it.fromLevel()))
                        .append("\",\"toLevel\":\"").append(esc(it.toLevel()))
                        .append("\",\"totalSpend\":").append(it.totalSpend()).append('}');
            }
            sb.append("]}");
            audit.record("LEVEL", "AUTO-UPGRADE", ACTOR, "AUTO_UPGRADE", sb.toString());
        }
        log.info("等级定时批处理完成：升级 {} 人", result.upgraded());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
