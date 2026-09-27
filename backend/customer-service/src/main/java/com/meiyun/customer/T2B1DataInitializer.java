package com.meiyun.customer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2-B1 数据治理演示种子（@Order(45)，M3-B8 用 44 顺链）。
 * 仅 meiyun_seed 库生效（JDBC URL 门控）；ruleRepository.count()>0 跳过幂等（一体事务同生共灭单门控）。
 * 10 规则＋8 问题单＋13 血缘节点＋13 边逐字锚定前端 mock（stores/t2DataGovern.ts seed()）。
 * mock 问题单状态/解决时刻含 Math.random() 不确定性，此处确定性抉择（验收链登记）：
 * 仅「客户手机号唯一」第 2 张（k=1）置 RESOLVED（resolved_at=4h 前），其余 7 张全 OPEN。
 * 时间语义逐字：lastCheckAt=enabled?hoursAgo(i+1):null；createdAt=daysAgo(60-i*2)；
 * issue detectedAt=hoursAgo((k+1)*6)。
 */
@Component
@Order(45)
public class T2B1DataInitializer implements ApplicationRunner {

    private final DataGovernRuleRepository ruleRepository;
    private final DataGovernIssueRepository issueRepository;
    private final DataLineageNodeRepository nodeRepository;
    private final DataLineageEdgeRepository edgeRepository;
    private final String datasourceUrl;

    public T2B1DataInitializer(DataGovernRuleRepository ruleRepository,
                               DataGovernIssueRepository issueRepository,
                               DataLineageNodeRepository nodeRepository,
                               DataLineageEdgeRepository edgeRepository,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.ruleRepository = ruleRepository;
        this.issueRepository = issueRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        if (ruleRepository.count() > 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();

        DataGovernRule r0 = seedRule(now, 0, "订单号非空", "orders", "order_no",
                DataGovernRule.TYPE_NOT_NULL, DataGovernRule.SEVERITY_HIGH,
                "order_no IS NOT NULL", true, "100", 0, "张数");
        DataGovernRule r1 = seedRule(now, 1, "客户手机号唯一", "customers", "phone",
                DataGovernRule.TYPE_UNIQUE, DataGovernRule.SEVERITY_HIGH,
                "COUNT(DISTINCT phone) = COUNT(*)", true, "98.4", 124, "张数");
        DataGovernRule r2 = seedRule(now, 2, "订单金额合理范围", "orders", "amount",
                DataGovernRule.TYPE_RANGE, DataGovernRule.SEVERITY_HIGH,
                "amount BETWEEN 0.01 AND 500000", true, "99.6", 18, "李析");
        DataGovernRule r3 = seedRule(now, 3, "手机号格式", "customers", "phone",
                DataGovernRule.TYPE_REGEX, DataGovernRule.SEVERITY_MEDIUM,
                "phone ~ '^1[3-9]\\d{9}$'", true, "96.2", 42, "李析");
        DataGovernRule r4 = seedRule(now, 4, "预约时间非过去", "appointments", "appoint_at",
                DataGovernRule.TYPE_CUSTOM, DataGovernRule.SEVERITY_MEDIUM,
                "appoint_at >= created_at", true, "99.1", 8, "王治");
        DataGovernRule r5 = seedRule(now, 5, "退款金额不超订单", "refunds", "amount",
                DataGovernRule.TYPE_CUSTOM, DataGovernRule.SEVERITY_HIGH,
                "refunds.amount <= orders.amount", true, "100", 0, "王治");
        DataGovernRule r6 = seedRule(now, 6, "身份证号格式", "customers", "id_card",
                DataGovernRule.TYPE_REGEX, DataGovernRule.SEVERITY_HIGH,
                "id_card ~ '^\\d{17}[\\dXx]$'", false, "94.8", 6, "王治");
        DataGovernRule r7 = seedRule(now, 7, "员工工号非空", "staff", "staff_no",
                DataGovernRule.TYPE_NOT_NULL, DataGovernRule.SEVERITY_MEDIUM,
                "staff_no IS NOT NULL", true, "100", 0, "张数");
        DataGovernRule r8 = seedRule(now, 8, "会员等级枚举", "customers", "level",
                DataGovernRule.TYPE_CUSTOM, DataGovernRule.SEVERITY_LOW,
                "level IN ('NORMAL','SILVER','GOLD','BLACK')", true, "99.9", 2, "张数");
        DataGovernRule r9 = seedRule(now, 9, "消费记录时间合法", "orders", "paid_at",
                DataGovernRule.TYPE_RANGE, DataGovernRule.SEVERITY_MEDIUM,
                "paid_at >= '2020-01-01' AND paid_at <= NOW()", true, "100", 0, "李析");

        seedIssue(now, r1, "phone='138****8000' 出现 3 次", 42, DataGovernIssue.STATUS_OPEN, 6, null);
        seedIssue(now, r1, "phone='138****8000' 出现 3 次", 42, DataGovernIssue.STATUS_RESOLVED, 12, 4L);
        seedIssue(now, r1, "phone='138****8000' 出现 3 次", 42, DataGovernIssue.STATUS_OPEN, 18, null);
        seedIssue(now, r2, "order_no=SO202608250091 amount=-200", 18, DataGovernIssue.STATUS_OPEN, 6, null);
        seedIssue(now, r3, "1380013800 (10位)", 42, DataGovernIssue.STATUS_OPEN, 6, null);
        seedIssue(now, r4, "appoint_at < created_at", 8, DataGovernIssue.STATUS_OPEN, 6, null);
        seedIssue(now, r6, "异常样本示例数据", 6, DataGovernIssue.STATUS_OPEN, 6, null);
        seedIssue(now, r8, "异常样本示例数据", 2, DataGovernIssue.STATUS_OPEN, 6, null);

        seedNode("src-mysql", "MySQL 交易库", DataLineageNode.TYPE_SOURCE, 40, 80);
        seedNode("src-pg", "PG 客户库", DataLineageNode.TYPE_SOURCE, 40, 220);
        seedNode("src-kafka", "Kafka 埋点", DataLineageNode.TYPE_SOURCE, 40, 360);
        seedNode("tab-orders", "dwd.orders", DataLineageNode.TYPE_TABLE, 260, 60);
        seedNode("tab-cust", "dwd.customers", DataLineageNode.TYPE_TABLE, 260, 200);
        seedNode("tab-events", "dwd.user_events", DataLineageNode.TYPE_TABLE, 260, 340);
        seedNode("tab-dws", "dws.customer_360", DataLineageNode.TYPE_TABLE, 480, 200);
        seedNode("tag-value", "高价值客户", DataLineageNode.TYPE_TAG, 700, 100);
        seedNode("tag-churn", "流失风险", DataLineageNode.TYPE_TAG, 700, 240);
        seedNode("tag-pref", "项目偏好", DataLineageNode.TYPE_TAG, 700, 380);
        seedNode("api-cust", "/api/v1/customer/profile", DataLineageNode.TYPE_API, 920, 80);
        seedNode("api-seg", "/api/v1/marketing/segment", DataLineageNode.TYPE_API, 920, 220);
        seedNode("rpt-dash", "经营驾驶舱", DataLineageNode.TYPE_REPORT, 920, 360);

        seedEdge("src-mysql", "tab-orders");
        seedEdge("src-pg", "tab-cust");
        seedEdge("src-kafka", "tab-events");
        seedEdge("tab-orders", "tab-dws");
        seedEdge("tab-cust", "tab-dws");
        seedEdge("tab-events", "tab-dws");
        seedEdge("tab-dws", "tag-value");
        seedEdge("tab-dws", "tag-churn");
        seedEdge("tab-dws", "tag-pref");
        seedEdge("tag-value", "api-cust");
        seedEdge("tag-churn", "api-seg");
        seedEdge("tag-pref", "rpt-dash");
        seedEdge("tag-value", "rpt-dash");
    }

    /** 单条规则种子（时间语义逐字对齐 mock：lastCheckAt=enabled?hoursAgo(i+1):null；createdAt=daysAgo(60-i*2)）。 */
    private DataGovernRule seedRule(OffsetDateTime now, int i, String name, String table, String column,
                                    String type, String severity, String expression, boolean enabled,
                                    String passRate, int errorCount, String owner) {
        DataGovernRule r = new DataGovernRule();
        r.setName(name);
        r.setTableName(table);
        r.setColumnName(column);
        r.setRuleType(type);
        r.setSeverity(severity);
        r.setExpression(expression);
        r.setEnabled(enabled);
        r.setLastCheckAt(enabled ? now.minusHours(i + 1L) : null);
        r.setPassRate(new BigDecimal(passRate));
        r.setErrorCount(errorCount);
        r.setOwner(owner);
        r.setCreatedAt(now.minusDays(60L - i * 2L));
        return ruleRepository.save(r);
    }

    /** 单条问题单种子（detectedAt=hoursAgo((k+1)*6) 由调用方逐字传入；resolvedHoursAgo 可空）。 */
    private void seedIssue(OffsetDateTime now, DataGovernRule rule, String sample, int count,
                           String status, long detectedHoursAgo, Long resolvedHoursAgo) {
        DataGovernIssue issue = new DataGovernIssue();
        issue.setRuleId(rule.getId());
        issue.setRuleName(rule.getName());
        issue.setTableName(rule.getTableName());
        issue.setColumnName(rule.getColumnName());
        issue.setSample(sample);
        issue.setErrorCount(count);
        issue.setStatus(status);
        issue.setDetectedAt(now.minusHours(detectedHoursAgo));
        issue.setResolvedAt(resolvedHoursAgo == null ? null : now.minusHours(resolvedHoursAgo));
        issueRepository.save(issue);
    }

    private void seedNode(String id, String name, String type, int x, int y) {
        DataLineageNode n = new DataLineageNode();
        n.setId(id);
        n.setName(name);
        n.setNodeType(type);
        n.setX(x);
        n.setY(y);
        nodeRepository.save(n);
    }

    private void seedEdge(String from, String to) {
        DataLineageEdge e = new DataLineageEdge();
        e.setFromNode(from);
        e.setToNode(to);
        edgeRepository.save(e);
    }
}
