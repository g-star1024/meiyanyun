package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2-B2 标签工厂演示种子（@Order(46)，T2-B1 用 45 顺链）。
 * 仅 meiyun_seed 库生效（JDBC URL 门控）；defRepository.count()>0 跳过幂等（一体事务同生共灭单门控）。
 * 10 条标签锚定前端 mock（stores/t2TagFactory.ts seed()）骨架＋consumerMap 4 条（tags[0] 三条/
 * tags[1] 两条/tags[2] 两条/tags[5] 两条）；棒⑥卡7 T2-03 收口：SQL/RULE 型表达式订正为真表真列
 * 可执行口径（txn_order/customer/referral 实测列与值域·金额分单位；TAG_REFERRAL_HIGH 因 RULE 引擎
 * 仅查 customer 表而改 SQL 型查 referral），ML 型引擎不执行故保留 mock 伪语法展示并如实标注估算口径；
 * mock doPublish coverCount 含 Math.random() 不确定性，此处按种子给定 coverCount 落库（确定性，验收链登记）。
 * 时间语义逐字：lastComputeAt=PUBLISHED?daysAgo(i%3):null；versions[0]={v1.0, publishedAt=daysAgo(30-i),
 * publishedBy=owner, coverCount=种子值}；createdAt=daysAgo(30-i)；updatedAt=daysAgo(i%5)。
 * PUBLISHED 7 条同事务同步 customer_tag（窄映射 RULE→行为/SQL→消费/ML→价值·name 查重软跳过·
 * TG### max+1 逐条取号，JPQL 查询前 AUTO flush 保证序可见；启动单线程无需 synchronized）。
 */
@Component
@Order(46)
public class T2B2DataInitializer implements ApplicationRunner {

    private final TagFactoryDefRepository defRepository;
    private final CustomerTagRepository customerTagRepository;
    private final ObjectMapper mapper;
    private final String datasourceUrl;

    public T2B2DataInitializer(TagFactoryDefRepository defRepository,
                               CustomerTagRepository customerTagRepository,
                               ObjectMapper mapper,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.defRepository = defRepository;
        this.customerTagRepository = customerTagRepository;
        this.mapper = mapper;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        if (defRepository.count() > 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<TagFactoryDef> seeded = new ArrayList<>();

        seeded.add(seedTag(now, 0, "TAG_HIGH_VALUE", "高价值客户", "客户价值",
                TagFactoryDef.TYPE_SQL, TagFactoryDef.SENS_PUBLIC, TagFactoryDef.VALUE_ENUM,
                "累计消费 ≥ 50,000 元的客户",
                "SELECT customer_id FROM txn_order WHERE status = '已收款' GROUP BY customer_id HAVING SUM(amount) >= 5000000",
                TagFactoryDef.STATUS_PUBLISHED, 1842, "每日 02:00", "张数"));
        seeded.add(seedTag(now, 1, "TAG_CHURN_RISK", "流失风险客户", "风险预警",
                TagFactoryDef.TYPE_RULE, TagFactoryDef.SENS_INTERNAL, TagFactoryDef.VALUE_BOOLEAN,
                "建档超 180 天且历史消费 > 10,000 元（流失近似口径）",
                "created_at < now() - interval '180 days' AND total_spend > 10000",
                TagFactoryDef.STATUS_PUBLISHED, 412, "每日 03:00", "张数"));
        seeded.add(seedTag(now, 2, "TAG_PRICE_SENSITIVE", "价格敏感型", "消费偏好",
                TagFactoryDef.TYPE_RULE, TagFactoryDef.SENS_PUBLIC, TagFactoryDef.VALUE_ENUM,
                "到店 ≥ 3 次但累计消费 < 5,000 元（价格敏感近似口径）",
                "visit_count >= 3 AND total_spend < 5000",
                TagFactoryDef.STATUS_PUBLISHED, 2134, "每周一 02:00", "李析"));
        seeded.add(seedTag(now, 3, "TAG_PROJECT_PREFER_LASER", "项目偏好-光电类", "消费偏好",
                TagFactoryDef.TYPE_SQL, TagFactoryDef.SENS_PUBLIC, TagFactoryDef.VALUE_NUMBER,
                "光电类项目消费 ≥ 2 次",
                "SELECT customer_id FROM txn_order WHERE project IN ('光子嫩肤','皮秒祛斑','激光祛痘','射频紧致','超声炮局部','热玛吉全面部') GROUP BY customer_id HAVING COUNT(*) >= 2",
                TagFactoryDef.STATUS_PUBLISHED, 1320, "每日 02:30", "张数"));
        seeded.add(seedTag(now, 4, "TAG_SLEEP_CUSTOMER", "沉睡客户", "活跃度",
                TagFactoryDef.TYPE_RULE, TagFactoryDef.SENS_PUBLIC, TagFactoryDef.VALUE_BOOLEAN,
                "建档超 90 天且从未到店（沉睡口径）",
                "created_at < now() - interval '90 days' AND visit_count = 0",
                TagFactoryDef.STATUS_PUBLISHED, 986, "每日 01:00", "张数"));
        seeded.add(seedTag(now, 5, "TAG_MEDICAL_AESTHETICS", "医美消费倾向", "AI 预测",
                TagFactoryDef.TYPE_ML, TagFactoryDef.SENS_INTERNAL, TagFactoryDef.VALUE_NUMBER,
                "基于浏览/咨询/消费行为预测医美消费倾向（0-100，估算口径待模型接入）",
                "PREDICT(aesthetic_propensity) USING model 'aesthetic_v2'",
                TagFactoryDef.STATUS_PUBLISHED, 3200, "每周一 04:00", "AI 模型组"));
        seeded.add(seedTag(now, 6, "TAG_NPS_PREDICT", "NPS 预测低分", "AI 预测",
                TagFactoryDef.TYPE_ML, TagFactoryDef.SENS_SENSITIVE, TagFactoryDef.VALUE_NUMBER,
                "预测 NPS ≤ 6 的客户（敏感，需审批；估算口径待模型接入）",
                "PREDICT(nps_score) USING model 'nps_v1' WHERE nps_score <= 6",
                TagFactoryDef.STATUS_PENDING, 0, "每周一 05:00", "AI 模型组"));
        seeded.add(seedTag(now, 7, "TAG_REPURCHASE_CYCLE", "复购周期标签", "消费行为",
                TagFactoryDef.TYPE_SQL, TagFactoryDef.SENS_PUBLIC, TagFactoryDef.VALUE_NUMBER,
                "订单 ≥ 2 次的复购客户",
                "SELECT customer_id FROM txn_order GROUP BY customer_id HAVING COUNT(*) >= 2",
                TagFactoryDef.STATUS_DRAFT, 0, "每日 03:30", "李析"));
        seeded.add(seedTag(now, 8, "TAG_REFERRAL_HIGH", "高转介绍价值", "客户价值",
                TagFactoryDef.TYPE_SQL, TagFactoryDef.SENS_PUBLIC, TagFactoryDef.VALUE_BOOLEAN,
                "成功转介绍成交 ≥ 1 人的推荐人客户",
                "SELECT referrer_customer_id FROM referral WHERE status = 'DEAL' GROUP BY referrer_customer_id HAVING COUNT(*) >= 1",
                TagFactoryDef.STATUS_PUBLISHED, 215, "每周一 02:00", "张数"));
        seeded.add(seedTag(now, 9, "TAG_INCOME_ESTIMATE", "收入水平估计", "AI 预测",
                TagFactoryDef.TYPE_ML, TagFactoryDef.SENS_SENSITIVE, TagFactoryDef.VALUE_ENUM,
                "基于消费行为预估客户收入区间（敏感数据；估算口径待模型接入）",
                "PREDICT(income_range) USING model 'income_v1'",
                TagFactoryDef.STATUS_OFFLINE, 0, "每月 1 日", "AI 模型组"));

        setConsumers(seeded.get(0), List.of(
                consumer("M3-06 标签体系", "客户分群", now.minusDays(1)),
                consumer("M3-12 流失预警", "高价值流失拦截", now.minusDays(2)),
                consumer("A1-08 智能营销", "高价值客户专属方案", now.minusDays(1))));
        setConsumers(seeded.get(1), List.of(
                consumer("M3-12 流失预警", "流失预警看板", now.minusDays(1)),
                consumer("M3-09 生日节日关怀", "流失客户挽回推送", now.minusDays(3))));
        setConsumers(seeded.get(2), List.of(
                consumer("M5-01 优惠券管理", "折扣券定向发放", now.minusDays(1)),
                consumer("M5-02 短信企微推送", "促销活动触达", now.minusDays(2))));
        setConsumers(seeded.get(5), List.of(
                consumer("A1-08 智能营销", "医美倾向客户推荐", now.minusDays(1)),
                consumer("A1-05 客户画像", "AI 画像标签", now.minusDays(1))));

        for (TagFactoryDef t : seeded) {
            if (TagFactoryDef.STATUS_PUBLISHED.equals(t.getStatus())
                    && !customerTagRepository.existsByTagName(t.getName())) {
                CustomerTag ct = new CustomerTag();
                ct.setTagId(nextTgId());
                ct.setTagName(t.getName());
                ct.setCategory(TagFactoryService.narrowCategory(t.getType()));
                customerTagRepository.save(ct);
            }
        }
    }

    /** 单条标签种子（时间语义逐字对齐 mock：lastComputeAt=PUBLISHED?daysAgo(i%3):null；createdAt=daysAgo(30-i)；updatedAt=daysAgo(i%5)）。 */
    private TagFactoryDef seedTag(OffsetDateTime now, int i, String code, String name, String category,
                                  String type, String sensitivity, String valueType, String description,
                                  String sql, String status, int coverCount, String refreshCron, String owner) {
        TagFactoryDef t = new TagFactoryDef();
        t.setCode(code);
        t.setName(name);
        t.setCategory(category);
        t.setType(type);
        t.setSensitivity(sensitivity);
        t.setValueType(valueType);
        t.setDescription(description);
        t.setSql(sql);
        t.setStatus(status);
        t.setCoverCount(coverCount);
        t.setRefreshCron(refreshCron);
        t.setOwner(owner);
        t.setLastComputeAt(TagFactoryDef.STATUS_PUBLISHED.equals(status) ? now.minusDays(i % 3L) : null);
        t.setVersions(TagFactoryDef.STATUS_PUBLISHED.equals(status)
                ? writeJson(List.of(Map.of(
                        "version", "v1.0",
                        "sql", sql,
                        "publishedAt", now.minusDays(30L - i).toString(),
                        "publishedBy", owner,
                        "coverCount", coverCount)))
                : "[]");
        t.setConsumers("[]");
        t.setTags("[]");
        t.setCreatedAt(now.minusDays(30L - i));
        t.setUpdatedAt(now.minusDays(i % 5L));
        return defRepository.save(t);
    }

    private static Map<String, String> consumer(String module, String scene, OffsetDateTime usedAt) {
        return Map.of("module", module, "scene", scene, "usedAt", usedAt.toString());
    }

    private void setConsumers(TagFactoryDef t, List<Map<String, String>> consumers) {
        t.setConsumers(writeJson(consumers));
        defRepository.save(t);
    }

    /** TG### 库内 max+1（定长 3 位序号；逐条取号依赖 JPQL 查询前 AUTO flush 见前序 save）。 */
    private String nextTgId() {
        String max = customerTagRepository.maxTgId();
        int seq = 0;
        if (max != null && max.startsWith("TG")) {
            try {
                seq = Integer.parseInt(max.substring(2));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return String.format("TG%03d", seq + 1);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
