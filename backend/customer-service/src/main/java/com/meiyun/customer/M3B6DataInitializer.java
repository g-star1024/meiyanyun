package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * M3-B6 启动播种（黑名单与风控 M3-17）：risk_record 六条（RK20260820001-RK20260805006）＋
 * risk_rule 五条（RR-1..RR-5），均与前端 risk store 种子活规格逐字一致
 * （createdAt 为当前时刻前 1-20 天/20 小时，时间线 JSONB 逐条还原 mock 相对时刻）。
 *
 * <p><b>栈门控</b>：演示数据仅与 seed 栈自洽，仅在种子库（JDBC URL 含 meiyun_seed）播种，
 * 与各 DataInitializer 同一门控约定；正式栈名单/规则由运营在 M3-17 页真实维护。
 */
@Component
@Order(43)
public class M3B6DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(M3B6DataInitializer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RiskRecordRepository recordRepo;
    private final RiskRuleRepository ruleRepo;
    private final String datasourceUrl;

    public M3B6DataInitializer(RiskRecordRepository recordRepo, RiskRuleRepository ruleRepo,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.recordRepo = recordRepo;
        this.ruleRepo = ruleRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过 M3-B6 演示数据播种；正式栈风控名单/规则由运营真实维护",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        seedRecords();
        seedRules();
    }

    /** risk_record 六条（count>0 跳过，幂等；字段/时间线照 mock 活规格逐字）。 */
    private void seedRecords() {
        if (recordRepo.count() > 0) {
            log.info("风控记录已存在（{} 条），跳过播种", recordRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        saveRecord("RK20260820001", "C-301", "赵某某", "135****0011", "HIGH", "FRAUD",
                "冒用他人会员卡到店核销，经核实身份不符", "BLACKLISTED", 3, true, "陈雅琳（店长）",
                now.minusDays(5), null, null,
                List.of(
                        tl("命中风控规则", "系统", now.minusDays(6), "非本人核销累计 2 次"),
                        tl("加入黑名单", "陈雅琳（店长）", now.minusDays(5), "核实身份不符，拉黑拦截交易")));
        saveRecord("RK20260822002", "C-302", "钱某", "136****0022", "HIGH", "CHARGEBACK",
                "近 30 天恶意退单 4 笔，涉及金额 ¥6,800", "PENDING_REVIEW", 4, false, "系统自动",
                now.minusHours(20), null, null,
                List.of(tl("系统检测到异常退单", "系统", now.minusHours(20), "30 天内退单率 80%")));
        saveRecord("RK20260818003", "C-303", "孙某", "137****0033", "MEDIUM", "MALICIOUS_COMPLAINT",
                "多次无依据投诉并要求超额赔偿", "WATCHING", 2, false, "林微（咨询师）",
                now.minusDays(7), null, null,
                List.of(tl("加入观察名单", "林微（咨询师）", now.minusDays(7), null)));
        saveRecord("RK20260810004", "C-304", "周某", "138****0044", "LOW", "ILLEGAL_PRACTICE",
                "疑似医托行为，引导客户至外院", "RELEASED", 1, false, "张磊（区域经理）",
                now.minusDays(15), now.minusDays(10), "张磊（区域经理）",
                List.of(
                        tl("加入观察", "张磊（区域经理）", now.minusDays(15), null),
                        tl("解除风险", "张磊（区域经理）", now.minusDays(10), "证据不足，解除观察")));
        saveRecord("RK20260824005", "C-305", "吴某", "139****0055", "MEDIUM", "OTHER",
                "短期内多次预约未到店，爽约率 90%", "WATCHING", 5, false, "系统自动",
                now.minusDays(1), null, null,
                List.of(tl("爽约率超阈值", "系统", now.minusDays(1), null)));
        saveRecord("RK20260805006", "C-306", "郑某", "133****0066", "HIGH", "FRAUD",
                "使用伪造优惠券被识别", "BLACKLISTED", 1, true, "陈雅琳（店长）",
                now.minusDays(20), null, null,
                List.of(tl("加入黑名单", "陈雅琳（店长）", now.minusDays(20), "伪造优惠券，拦截交易")));
        log.info("风控记录播种：6 条（已拉黑 2/待审核 1/观察中 2/已解除 1）");
    }

    /** risk_rule 五条（count>0 跳过，幂等；编号/文案/命中数照 mock 活规格逐字）。 */
    private void seedRules() {
        if (ruleRepo.count() > 0) {
            log.info("风控规则已存在（{} 条），跳过播种", ruleRepo.count());
            return;
        }
        saveRule("RR-1", "非本人会员卡核销", "同一会员卡被不同身份人员核销达 2 次即预警", true, "REVIEW", 12);
        saveRule("RR-2", "高频恶意退单", "30 天内退单 ≥3 笔且退单率 >50% 自动拉黑待审", true, "BLOCK", 4);
        saveRule("RR-3", "伪造优惠券/兑换码", "核销时校验失败累计 1 次即拦截", true, "BLOCK", 2);
        saveRule("RR-4", "高爽约率", "近 90 天爽约率 >70% 加入观察名单", true, "WARN", 8);
        saveRule("RR-5", "医托导流识别", "同一联系方式关联多家外院导流行为", false, "REVIEW", 0);
        log.info("风控规则播种：5 条（RR-1..RR-5，启用 4/停用 1）");
    }

    private void saveRecord(String riskNo, String customerId, String customerName, String phoneMask,
                            String level, String reason, String reasonDetail, String status,
                            int hitCount, boolean blockTransactions, String operator,
                            OffsetDateTime createdAt, OffsetDateTime resolvedAt, String resolvedBy,
                            List<Map<String, Object>> timeline) {
        RiskRecord r = new RiskRecord();
        r.setRiskNo(riskNo);
        r.setCustomerId(customerId);
        r.setCustomerName(customerName);
        r.setPhoneMask(phoneMask);
        r.setLevel(level);
        r.setReason(reason);
        r.setReasonDetail(reasonDetail);
        r.setStatus(status);
        r.setHitCount(hitCount);
        r.setBlockTransactions(blockTransactions);
        r.setOperator(operator);
        r.setResolvedAt(resolvedAt);
        r.setResolvedBy(resolvedBy);
        r.setTimeline(toJson(timeline));
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(createdAt);
        recordRepo.save(r);
    }

    private void saveRule(String ruleNo, String name, String description, boolean enabled,
                          String action, int hitCount) {
        RiskRule r = new RiskRule();
        r.setRuleNo(ruleNo);
        r.setName(name);
        r.setDescription(description);
        r.setEnabled(enabled);
        r.setAction(action);
        r.setHitCount(hitCount);
        ruleRepo.save(r);
    }

    private static Map<String, Object> tl(String action, String by, OffsetDateTime at, String comment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("by", by);
        m.put("at", at.toString());
        if (comment != null) {
            m.put("comment", comment);
        }
        return m;
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
