package com.meiyun.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 目标管理域种子（B49 卡8，DELIVERY-P5-B49 §卡8）：biz_target 为空时幂等播种。
 *
 * <p>照前端 m1Target mock 全量 10 行：G1 集团营收年度 38000/23800 万元（children 三区域）、
 * G1-E 华东 18000/11900（children T01-R 杭州 10000/6800 + T02-R 上海静安 8000/5100）、
 * G1-N 华北 11000/5900、G1-S 华南 9000/6000、G2 新客 Q3 4200/1980 人、
 * G3 复购率 Q3 45/41 % PENDING、G4 满意度 2026-08 95/93 %、
 * G5 华南治疗人次 Q3 3000/0 人次 DRAFT——覆盖审批中/草稿两样本态。
 * target_id 保留 mock 可读业务 id（G1/G1-E/T01-R 风格），前端树按字符串 id 引用零适配。
 *
 * <p><b>栈门控</b>：与 CommissionDataInitializer 同规——仅种子库（JDBC URL 含 meiyun_seed）
 * 播种；prod 启动跳过，数据由页面 CRUD 录入。
 */
@Component
@Order(45)
public class BizTargetDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BizTargetDataInitializer.class);

    private final BizTargetRepository targetRepo;
    private final String datasourceUrl;

    public BizTargetDataInitializer(BizTargetRepository targetRepo,
                                    @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.targetRepo = targetRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过目标管理演示数据播种；如需演示数据请在页面录入",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (targetRepo.count() > 0) {
            log.info("经营目标已存在（{} 条），跳过目标播种", targetRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<BizTarget> rows = new ArrayList<>();
        // 集团年度营收目标（children 三区域）
        rows.add(target("G1", "GROUP", "美云集团", "GROUP", "REVENUE", "YEAR", "2026年度",
                "38000", "23800", "万元", 40, "APPROVED", "G1-E,G1-N,G1-S", now));
        rows.add(target("G1-E", "R-EAST", "华东区", "REGION", "REVENUE", "YEAR", "2026年度",
                "18000", "11900", "万元", 40, "APPROVED", "T01-R,T02-R", now));
        rows.add(target("G1-N", "R-NORTH", "华北区", "REGION", "REVENUE", "YEAR", "2026年度",
                "11000", "5900", "万元", 40, "APPROVED", null, now));
        rows.add(target("G1-S", "R-SOUTH", "华南区", "REGION", "REVENUE", "YEAR", "2026年度",
                "9000", "6000", "万元", 40, "APPROVED", null, now));
        rows.add(target("T01-R", "T01", "杭州西湖旗舰院", "STORE", "REVENUE", "YEAR", "2026年度",
                "10000", "6800", "万元", 40, "APPROVED", null, now));
        rows.add(target("T02-R", "T02", "上海静安分院", "STORE", "REVENUE", "YEAR", "2026年度",
                "8000", "5100", "万元", 40, "APPROVED", null, now));
        // 独立集团指标
        rows.add(target("G2", "GROUP", "美云集团", "GROUP", "NEW_CUSTOMER", "QUARTER", "2026-Q3",
                "4200", "1980", "人", 20, "APPROVED", null, now));
        rows.add(target("G3", "GROUP", "美云集团", "GROUP", "REPURCHASE_RATE", "QUARTER", "2026-Q3",
                "45", "41", "%", 15, "PENDING", null, now));
        rows.add(target("G4", "GROUP", "美云集团", "GROUP", "SATISFACTION", "MONTH", "2026-08",
                "95", "93", "%", 15, "APPROVED", null, now));
        rows.add(target("G5", "R-SOUTH", "华南区", "REGION", "PROCEDURE_COUNT", "QUARTER", "2026-Q3",
                "3000", "0", "人次", 10, "DRAFT", null, now));
        targetRepo.saveAll(rows);
        log.info("经营目标播种完成：{} 条（集团/区域/门店三级 + 独立指标，覆盖已批准/待审批/草稿）", rows.size());
    }

    private BizTarget target(String targetId, String ownerId, String ownerName, String ownerType,
                             String metric, String period, String periodLabel,
                             String targetValue, String currentValue, String unit, int weight,
                             String approval, String childrenIds, OffsetDateTime now) {
        BizTarget t = new BizTarget();
        t.setTargetId(targetId);
        t.setOwnerId(ownerId);
        t.setOwnerName(ownerName);
        t.setOwnerType(ownerType);
        t.setMetric(metric);
        t.setPeriod(period);
        t.setPeriodLabel(periodLabel);
        t.setTargetValue(new BigDecimal(targetValue));
        t.setCurrentValue(new BigDecimal(currentValue));
        t.setUnit(unit);
        t.setWeight(weight);
        t.setApproval(approval);
        t.setChildrenIds(childrenIds);
        if ("APPROVED".equals(approval)) {
            t.setSubmittedBy("system");
            t.setSubmittedAt(now.minusDays(2));
            t.setApprovedBy("SE101");
            t.setApprovedAt(now.minusDays(1));
        }
        if ("PENDING".equals(approval)) {
            t.setSubmittedBy("system");
            t.setSubmittedAt(now.minusDays(1));
        }
        t.setCreatedBy("system");
        t.setUpdatedBy("system");
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }
}
