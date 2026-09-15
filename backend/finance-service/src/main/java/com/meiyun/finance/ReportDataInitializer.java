package com.meiyun.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报表中心种子（B49 卡11，DELIVERY-P5-B49 §卡11）：report_template / report_job 为空时幂等播种。
 *
 * <p>照前端 m1Report mock 全量原值：9 模板（R01-R09，subscribed 五 true=R01/R02/R04/R06/R08，
 * lastRunAt 照 mock 串，R03 为 null）+ 5 任务（J01-J05，fileSize 由 mock '248 KB' 等串反算字节
 * 253952/159744/43008/100352；J04 FAILED 带 error 原文）。<b>content 全部 NULL 不伪造</b>——
 * 历史文件下载走 404「历史文件未留存，请重新生成」提示路径。
 *
 * <p><b>栈门控</b>：与 BizTargetDataInitializer 同规——仅种子库（JDBC URL 含 meiyun_seed）播种；
 * prod 启动跳过，数据由页面操作沉淀。
 */
@Component
@Order(85)
public class ReportDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportDataInitializer.class);

    private final ReportTemplateRepository tplRepo;
    private final ReportJobRepository jobRepo;
    private final String datasourceUrl;

    public ReportDataInitializer(ReportTemplateRepository tplRepo, ReportJobRepository jobRepo,
                                 @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.tplRepo = tplRepo;
        this.jobRepo = jobRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过报表中心演示数据播种",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (tplRepo.count() > 0 || jobRepo.count() > 0) {
            log.info("报表模板/任务已存在（{} / {} 条），跳过报表播种", tplRepo.count(), jobRepo.count());
            return;
        }

        List<ReportTemplate> templates = List.of(
                tpl("R01", "门店营收日报", "REVENUE", "按门店/支付方式汇总当日营收、客单价、笔数",
                        "DAY", "门店,支付方式", "营收,客单价,笔数", ts("2026-08-25 09:00"), true),
                tpl("R02", "月度经营分析报告", "REVENUE", "集团/区域/门店三级营收、成本、毛利、环比同比",
                        "MONTH", "区域,门店,项目品类", "营收,成本,毛利率,环比", ts("2026-08-01"), true),
                tpl("R03", "新客转化漏斗", "CUSTOMER", "到店→咨询→成交各环节转化率与流失分析",
                        "WEEK", "渠道,门店", "到店数,咨询数,成交数,转化率", null, false),
                tpl("R04", "客户复购与 RFM 分层", "CUSTOMER", "RFM 分层、复购率、沉睡客户、生命周期价值",
                        "MONTH", "门店,客户分层", "复购率,客单价,LTV", ts("2026-08-03"), true),
                tpl("R05", "项目疗程消耗报表", "OPERATION", "卡项/疗程剩余次数、核销率、即将到期预警",
                        "MONTH", "门店,项目品类", "剩余次数,核销率,到期数", ts("2026-08-20"), false),
                tpl("R06", "应收账款账龄分析", "FINANCE", "按账龄区间统计应收、逾期、坏账拨备",
                        "MONTH", "门店,账龄区间", "应收余额,逾期金额,逾期率", ts("2026-08-01"), true),
                tpl("R07", "退款与纠纷台账", "FINANCE", "退款金额、原因分布、处理时效、纠纷升级",
                        "MONTH", "门店,退款原因", "退款笔数,退款金额,处理时长", ts("2026-08-24"), false),
                tpl("R08", "合规检查月报", "COMPLIANCE", "资质、知情同意、药品溯源、隐私合规检查结果",
                        "MONTH", "门店,合规项", "通过率,问题数,整改率", ts("2026-08-01"), true),
                tpl("R09", "员工业绩排行", "STAFF", "咨询师/医生业绩、提薪、服务人次、满意度",
                        "MONTH", "门店,员工", "业绩,服务人次,满意度,提成", ts("2026-08-01"), false));
        tplRepo.saveAll(templates);

        List<ReportJob> jobs = List.of(
                job("J01", "R02", "月度经营分析报告", "REVENUE", "2026-07", "READY", "XLSX",
                        ts("2026-08-01 08:15"), "系统（订阅）", 1284, 253952, null),
                job("J02", "R06", "应收账款账龄分析", "FINANCE", "2026-07", "READY", "PDF",
                        ts("2026-08-01 09:30"), "刘财务", 326, 159744, null),
                job("J03", "R01", "门店营收日报", "REVENUE", "2026-08-24", "READY", "XLSX",
                        ts("2026-08-25 09:00"), "系统（订阅）", 58, 43008, null),
                job("J04", "R08", "合规检查月报", "COMPLIANCE", "2026-07", "FAILED", "PDF",
                        ts("2026-08-01 10:12"), "王质控", null, null, "部分门店数据未同步，请重试"),
                job("J05", "R09", "员工业绩排行", "STAFF", "2026-07", "READY", "XLSX",
                        ts("2026-08-02 14:20"), "张经理", 86, 100352, null));
        jobRepo.saveAll(jobs);
        log.info("报表中心播种完成：模板 {} 条、历史任务 {} 条（content 全部 NULL 不伪造）",
                templates.size(), jobs.size());
    }

    private ReportTemplate tpl(String id, String name, String category, String description,
                               String period, String dimensions, String metrics,
                               OffsetDateTime lastRunAt, boolean subscribed) {
        ReportTemplate t = new ReportTemplate();
        t.setId(id);
        t.setName(name);
        t.setCategory(category);
        t.setDescription(description);
        t.setPeriod(period);
        t.setDimensions(dimensions);
        t.setMetrics(metrics);
        t.setLastRunAt(lastRunAt);
        t.setSubscribed(subscribed);
        return t;
    }

    private ReportJob job(String id, String templateId, String templateName, String category,
                          String period, String status, String format, OffsetDateTime createdAt,
                          String createdBy, Integer rowCount, Integer fileSize, String error) {
        ReportJob j = new ReportJob();
        j.setId(id);
        j.setTemplateId(templateId);
        j.setTemplateName(templateName);
        j.setCategory(category);
        j.setPeriod(period);
        j.setStatus(status);
        j.setFormat(format);
        j.setCreatedAt(createdAt);
        j.setCreatedBy(createdBy);
        j.setRowCount(rowCount);
        j.setFileSize(fileSize);
        j.setError(error);
        j.setContent(null);
        return j;
    }

    /** mock 时间串（'yyyy-MM-dd HH:mm' 或 'yyyy-MM-dd'）→ +08:00 OffsetDateTime。 */
    private static OffsetDateTime ts(String s) {
        String v = s.trim();
        if (v.length() == 10) {
            v = v + " 00:00";
        }
        return LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                .atOffset(ZoneOffset.ofHours(8));
    }
}
