package com.meiyun.finance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 薪酬提成域种子（B9，DESIGN §5.6）：commission_rule / staff_comp_config 为空时幂等播种。
 *
 * <p>规则：r1 咨询师划扣超额累进 6%/8%/10%/12%（8万/15万/25万起跳）、r2 医生 10%/12%（10万起跳）；
 * 薪酬配置：6 店在岗咨询师（SE002/006/010/014/018/022）配 r1、医生（SE003/007/011/015/019/023）
 * 配 r2，月底薪 8000-15000 元（分）；提成单：当月 12 人演示单覆盖 DRAFT/SUBMITTED/APPROVED/PAID
 * 全状态机（writeoff_record 无种子业绩，演示单基数为演示快照，用户点「生成试算」后按真实业绩重算）。
 * 金额口径：底薪/基数/提成 bigint 存「分」；rate 万分位；月份契约 yyyy-MM-01。
 * 种子用固定 ID（CR-SEED-xx / SC-SEED-xxx / CM-SEED-yyMM-xxx），用户新建走 CommissionNoGenerator。
 *
 * <p><b>栈门控</b>：演示数据门店码/工号（SST01-06、SE00x）与 seed 栈主数据（01_master.sql）自洽；
 * prod 栈主数据为 ST-XX-NNN，且 revenue_monthly.store_code 有外键指向门店主数据，
 * 若把 SST 演示数据播进 prod，B11 结转刷新 revenue_monthly 会撞 FK（SQLState 23503）。
 * 故仅在种子库（JDBC URL 含 meiyun_seed）播种；prod 启动跳过，数据由页面 CRUD 录入。
 */
@Component
@Order(40)
public class CommissionDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommissionDataInitializer.class);

    private final CommissionRuleRepository ruleRepo;
    private final StaffCompConfigRepository compRepo;
    private final CommissionRecordRepository recordRepo;
    private final String datasourceUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public CommissionDataInitializer(CommissionRuleRepository ruleRepo,
                                     StaffCompConfigRepository compRepo,
                                     CommissionRecordRepository recordRepo,
                                     @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.ruleRepo = ruleRepo;
        this.compRepo = compRepo;
        this.recordRepo = recordRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过薪酬提成演示数据播种；如需演示数据请在页面录入",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        seedRules();
        seedCompConfigs();
        seedRecords();
    }

    private void seedRules() {
        if (ruleRepo.count() > 0) {
            log.info("提成规则已存在（{} 条），跳过规则播种", ruleRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<CommissionRule> rules = new ArrayList<>();
        rules.add(rule("CR-SEED-001", "咨询师划扣提成阶梯（6/8/10/12%）", "WRITEOFF", "CONSULTANT",
                List.of(tier(0L, 600, "0-8万 6%"), tier(8_000_000L, 800, "8-15万 8%"),
                        tier(15_000_000L, 1000, "15-25万 10%"), tier(25_000_000L, 1200, "25万以上 12%")), now));
        rules.add(rule("CR-SEED-002", "医生划扣提成阶梯（10/12%）", "WRITEOFF", "DOCTOR",
                List.of(tier(0L, 1000, "0-10万 10%"), tier(10_000_000L, 1200, "10万以上 12%")), now));
        ruleRepo.saveAll(rules);
        log.info("提成规则播种完成：{} 条（咨询师阶梯 / 医生阶梯）", rules.size());
    }

    private void seedCompConfigs() {
        if (compRepo.count() > 0) {
            log.info("薪酬配置已存在（{} 条），跳过薪酬播种", compRepo.count());
            return;
        }
        LocalDate eff = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        OffsetDateTime now = OffsetDateTime.now();
        List<StaffCompConfig> configs = new ArrayList<>();
        // 咨询师（r1）：工号 姓名 门店 月底薪(元)
        configs.add(comp("SC-SEED-001", "SE002", "林咨询", "SST01", 10_000, "CR-SEED-001", eff, now));
        configs.add(comp("SC-SEED-002", "SE006", "沈咨询", "SST02", 12_000, "CR-SEED-001", eff, now));
        configs.add(comp("SC-SEED-003", "SE010", "曹咨询", "SST03", 9_000, "CR-SEED-001", eff, now));
        configs.add(comp("SC-SEED-004", "SE014", "魏咨询", "SST04", 11_000, "CR-SEED-001", eff, now));
        configs.add(comp("SC-SEED-005", "SE018", "谢咨询", "SST05", 10_000, "CR-SEED-001", eff, now));
        configs.add(comp("SC-SEED-006", "SE022", "水咨询", "SST06", 8_000, "CR-SEED-001", eff, now));
        // 医生（r2）
        configs.add(comp("SC-SEED-007", "SE003", "江医生", "SST01", 15_000, "CR-SEED-002", eff, now));
        configs.add(comp("SC-SEED-008", "SE007", "古医生", "SST02", 14_000, "CR-SEED-002", eff, now));
        configs.add(comp("SC-SEED-009", "SE011", "严医生", "SST03", 13_000, "CR-SEED-002", eff, now));
        configs.add(comp("SC-SEED-010", "SE015", "陶医生", "SST04", 15_000, "CR-SEED-002", eff, now));
        configs.add(comp("SC-SEED-011", "SE019", "邹医生", "SST05", 12_000, "CR-SEED-002", eff, now));
        configs.add(comp("SC-SEED-012", "SE023", "窦医生", "SST06", 13_000, "CR-SEED-002", eff, now));
        compRepo.saveAll(configs);
        log.info("薪酬配置播种完成：{} 条（6 咨询师 + 6 医生，底薪 8000-15000 元）", configs.size());
    }

    private void seedRecords() {
        if (recordRepo.count() > 0) {
            log.info("提成单已存在（{} 条），跳过提成单播种", recordRepo.count());
            return;
        }
        LocalDate month = LocalDate.now().withDayOfMonth(1);
        OffsetDateTime now = OffsetDateTime.now();
        List<CommissionRecord> records = new ArrayList<>();
        // 工号 姓名 门店 规则ID 规则名 基数(元) 单数 提成(元) 状态
        records.add(rec(month, "SE006", "沈咨询", "SST02", "CR-SEED-001", "咨询师划扣提成阶梯（6/8/10/12%）",
                186_000, 42, 12_480, "DRAFT", now));
        records.add(rec(month, "SE007", "古医生", "SST02", "CR-SEED-002", "医生划扣提成阶梯（10/12%）",
                238_000, 36, 28_560, "SUBMITTED", now));
        records.add(rec(month, "SE002", "林咨询", "SST01", "CR-SEED-001", "咨询师划扣提成阶梯（6/8/10/12%）",
                96_500, 28, 6_120, "APPROVED", now));
        records.add(rec(month, "SE003", "江医生", "SST01", "CR-SEED-002", "医生划扣提成阶梯（10/12%）",
                152_000, 24, 18_240, "PAID", now));
        records.add(rec(month, "SE014", "魏咨询", "SST04", "CR-SEED-001", "咨询师划扣提成阶梯（6/8/10/12%）",
                72_000, 21, 4_320, "DRAFT", now));
        records.add(rec(month, "SE015", "陶医生", "SST04", "CR-SEED-002", "医生划扣提成阶梯（10/12%）",
                118_000, 19, 14_160, "APPROVED", now));
        records.add(rec(month, "SE010", "曹咨询", "SST03", "CR-SEED-001", "咨询师划扣提成阶梯（6/8/10/12%）",
                45_000, 15, 2_700, "REJECTED", now));
        records.add(rec(month, "SE011", "严医生", "SST03", "CR-SEED-002", "医生划扣提成阶梯（10/12%）",
                88_000, 14, 8_800, "DRAFT", now));
        records.add(rec(month, "SE018", "谢咨询", "SST05", "CR-SEED-001", "咨询师划扣提成阶梯（6/8/10/12%）",
                128_000, 33, 8_640, "SUBMITTED", now));
        records.add(rec(month, "SE019", "邹医生", "SST05", "CR-SEED-002", "医生划扣提成阶梯（10/12%）",
                166_000, 27, 19_920, "APPROVED", now));
        records.add(rec(month, "SE022", "水咨询", "SST06", "CR-SEED-001", "咨询师划扣提成阶梯（6/8/10/12%）",
                38_000, 12, 2_280, "DRAFT", now));
        records.add(rec(month, "SE023", "窦医生", "SST06", "CR-SEED-002", "医生划扣提成阶梯（10/12%）",
                76_000, 13, 7_600, "DRAFT", now));
        for (CommissionRecord r : records) {
            r.setRecordId("CM-SEED-" + month.toString().substring(0, 7).replace("-", "") + "-" + r.getStaffId());
            r.setTiersJson(seedTiersJson(r.getBaseAmount(), r.getCommission()));
        }
        recordRepo.saveAll(records);
        log.info("提成单播种完成：{} 条（当月演示单，覆盖待提交/待审批/已审批/已发放/已驳回）", records.size());
    }

    private CommissionRule rule(String id, String name, String base, String role,
                                List<Map<String, Object>> tiers, OffsetDateTime now) {
        CommissionRule r = new CommissionRule();
        r.setRuleId(id);
        r.setRuleName(name);
        r.setBase(base);
        r.setRole(role);
        r.setTiersJson(json(tiers));
        r.setActive(true);
        r.setCreatedBy("system");
        r.setUpdatedBy("system");
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    private StaffCompConfig comp(String id, String staffId, String staffName, String storeCode,
                                 long baseSalaryYuan, String ruleId, LocalDate eff, OffsetDateTime now) {
        StaffCompConfig c = new StaffCompConfig();
        c.setCompId(id);
        c.setStaffId(staffId);
        c.setStaffName(staffName);
        c.setStoreCode(storeCode);
        c.setBaseSalary(baseSalaryYuan * 100);
        c.setCommissionRuleId(ruleId);
        c.setEffectiveMonth(eff);
        c.setStatus("ACTIVE");
        c.setCreatedBy("system");
        c.setUpdatedBy("system");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private CommissionRecord rec(LocalDate month, String staffId, String staffName, String storeCode,
                                 String ruleId, String ruleName, long baseYuan, int count,
                                 long commissionYuan, String status, OffsetDateTime now) {
        CommissionRecord r = new CommissionRecord();
        r.setPeriod(month);
        r.setStaffId(staffId);
        r.setStaffName(staffName);
        r.setStoreCode(storeCode);
        r.setRuleId(ruleId);
        r.setRuleName(ruleName);
        r.setBaseAmount(baseYuan * 100);
        r.setOrderCount(count);
        r.setCommission(commissionYuan * 100);
        r.setStatus(status);
        r.setCreatedAt(now);
        if ("APPROVED".equals(status) || "PAID".equals(status)) {
            r.setApprover("SE101");
            r.setApprovedAt(now.minusDays(1));
        }
        if ("PAID".equals(status)) {
            r.setPaidAt(now.minusHours(6));
        }
        if ("REJECTED".equals(status)) {
            r.setApprover("SE101");
            r.setApprovedAt(now.minusDays(1));
            r.setRemark("演示驳回：业绩归属待核实");
        }
        return r;
    }

    private static Map<String, Object> tier(long min, int rate, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("min", min);
        m.put("rate", rate);
        m.put("label", label);
        return m;
    }

    /** 演示单试算快照（基数/提成总额快照；点「生成试算」后按真实规则重算覆盖）。 */
    private String seedTiersJson(Long baseAmount, Long commission) {
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("label", "演示基数合计");
        seg.put("amount", baseAmount);
        seg.put("commission", commission);
        return json(List.of(seg));
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
