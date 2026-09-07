package com.meiyun.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 月结成本结转域种子（B11，DESIGN §四）：cost_carry_rule / fin_asset 为空时幂等播种。
 *
 * <p>三条启用规则（全门店通用、封账自动结转）：
 * <ul>
 *   <li>CCR-SEED-001 设备折旧自动计提：DEPRECIATION/ASSET，fin_asset 直线法月折；</li>
 *   <li>CCR-SEED-002 月底人工底薪结转：LABOR/BASE_SALARY，在岗咨询师/医生月底薪合计；</li>
 *   <li>CCR-SEED-003 月底人工提成结转：LABOR/COMMISSION，当月已审批/已发放提成合计。</li>
 * </ul>
 *
 * <p>徐汇店（SST01）光电设备资产 3 项（残值率 5%、10 年直线）：
 * 皮秒激光仪 50 万（月折 395833 分）、热玛吉 FLX 40 万（月折 316667 分）、
 * 光子嫩肤仪 20 万（月折 158333 分），合计月折 870833 分；起折月为上月，当月即计提。
 * 金额口径：original_value/fixed_amount bigint 存「分」；salvage_rate 百分比整数。
 * 种子用固定 ID（CCR-SEED-xxx / FA-SEED-xxx），用户新建走 nextRuleNo/nextAssetNo。
 *
 * <p><b>栈门控</b>：演示规则配套的资产/薪酬/提成数据门店码为 SST01-06（seed 栈主数据自洽）；
 * prod 栈主数据为 ST-XX-NNN，且结转刷新 revenue_monthly 时 store_code 受外键约束，
 * SST 演示数据在 prod 会导致 carry/run 撞 FK（SQLState 23503）。
 * 故仅在种子库（JDBC URL 含 meiyun_seed）播种；prod 启动跳过，规则/资产由页面 CRUD 录入。
 */
@Component
@Order(50)
public class CostCarryDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CostCarryDataInitializer.class);

    private final CostCarryRuleRepository ruleRepo;
    private final FinAssetRepository assetRepo;
    private final String datasourceUrl;

    public CostCarryDataInitializer(CostCarryRuleRepository ruleRepo, FinAssetRepository assetRepo,
                                    @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.ruleRepo = ruleRepo;
        this.assetRepo = assetRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过月结结转演示数据播种；规则/资产请在期末结转页面录入",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        seedRules();
        seedAssets();
    }

    private void seedRules() {
        if (ruleRepo.count() > 0) {
            log.info("结转规则已存在（{} 条），跳过结转规则播种", ruleRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<CostCarryRule> rules = new ArrayList<>();
        rules.add(rule("CCR-SEED-001", "设备折旧自动计提", "DEPRECIATION", "ASSET",
                "期末按设备资产台账直线法计提月折旧（残值率 5%、按折旧月限均摊）", now));
        rules.add(rule("CCR-SEED-002", "月底人工底薪结转", "LABOR", "BASE_SALARY",
                "期末按在岗咨询师/医生薪酬配置结转月底薪合计", now));
        rules.add(rule("CCR-SEED-003", "月底人工提成结转", "LABOR", "COMMISSION",
                "期末结转当月已审批/已发放提成合计（DRAFT/待审批不计提）", now));
        ruleRepo.saveAll(rules);
        log.info("结转规则播种完成：{} 条（设备折旧 / 底薪 / 提成，均启用且封账自动结转）", rules.size());
    }

    private void seedAssets() {
        if (assetRepo.count() > 0) {
            log.info("设备资产已存在（{} 项），跳过资产播种", assetRepo.count());
            return;
        }
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(1);
        OffsetDateTime now = OffsetDateTime.now();
        List<FinAsset> assets = new ArrayList<>();
        // 徐汇店光电设备：名称 原值(元) 残值率% 月限
        assets.add(asset("FA-SEED-001", "皮秒激光仪", "SST01", 500_000, 5, 120, start, now));
        assets.add(asset("FA-SEED-002", "热玛吉 FLX 射频仪", "SST01", 400_000, 5, 120, start, now));
        assets.add(asset("FA-SEED-003", "光子嫩肤仪", "SST01", 200_000, 5, 120, start, now));
        assetRepo.saveAll(assets);
        long monthly = assets.stream().mapToLong(FinAsset::monthlyDepreciation).sum();
        log.info("设备资产播种完成：{} 项（徐汇店光电设备，合计月折 {} 分）", assets.size(), monthly);
    }

    private CostCarryRule rule(String id, String name, String costType, String calcMode,
                               String remark, OffsetDateTime now) {
        CostCarryRule r = new CostCarryRule();
        r.setRuleId(id);
        r.setRuleName(name);
        r.setCostType(costType);
        r.setCalcMode(calcMode);
        r.setFixedAmount(null);
        r.setStoreCode(null);
        r.setEnabled(true);
        r.setRunOnClose(true);
        r.setRemark(remark);
        r.setCreatedBy("system");
        r.setUpdatedBy("system");
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    private FinAsset asset(String id, String name, String storeCode, long originalYuan,
                           int salvageRate, int usefulMonths, LocalDate startMonth, OffsetDateTime now) {
        FinAsset a = new FinAsset();
        a.setAssetId(id);
        a.setAssetName(name);
        a.setStoreCode(storeCode);
        a.setOriginalValue(originalYuan * 100);
        a.setSalvageRate(salvageRate);
        a.setUsefulMonths(usefulMonths);
        a.setStartMonth(startMonth);
        a.setStatus("IN_USE");
        a.setCreatedBy("system");
        a.setUpdatedBy("system");
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return a;
    }
}
