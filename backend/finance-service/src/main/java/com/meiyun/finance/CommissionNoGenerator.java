package com.meiyun.finance;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 薪酬提成域单号生成器：
 * <ul>
 *   <li>SC 薪酬配置号：SC + yyyyMMdd + - + 6 位当日序号；</li>
 *   <li>CR 提成规则号：CR + yyyyMMdd + - + 6 位当日序号；</li>
 *   <li>CM 提成单号：CM + yyyyMM + - + 工号（月度 per 人唯一，UNIQUE(period,staff_id) 幂等）。</li>
 * </ul>
 * 集中 synchronized 生成，序号取仓库当日/当月最大序号 +1（异常回落 0），禁 AtomicLong（重启丢号）。
 */
@Component
public class CommissionNoGenerator {

    private final StaffCompConfigRepository compRepo;
    private final CommissionRuleRepository ruleRepo;

    public CommissionNoGenerator(StaffCompConfigRepository compRepo, CommissionRuleRepository ruleRepo) {
        this.compRepo = compRepo;
        this.ruleRepo = ruleRepo;
    }

    /** 薪酬配置号：SC + yyyyMMdd + - + 6 位。 */
    public synchronized String nextCompNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = compRepo.maxSeqOfDay("SC" + day + "-%");
        } catch (Exception ignore) {
        }
        return "SC" + day + "-" + String.format("%06d", max + 1);
    }

    /** 提成规则号：CR + yyyyMMdd + - + 6 位。 */
    public synchronized String nextRuleNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = ruleRepo.maxSeqOfDay("CR" + day + "-%");
        } catch (Exception ignore) {
        }
        return "CR" + day + "-" + String.format("%06d", max + 1);
    }

    /** 提成单号：CM + yyyyMM + - + 工号（同月同人唯一，幂等重算沿用此号）。 */
    public String commissionNo(LocalDate period, String staffId) {
        String month = period.toString().replace("-", "").substring(0, 6);
        return "CM" + month + "-" + staffId;
    }
}
