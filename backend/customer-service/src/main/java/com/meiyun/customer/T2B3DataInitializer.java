package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2-B3 数据服务演示种子（@Order(47)，T2-B1 用 45、T2-B2 用 46 顺链）。
 * 仅 meiyun_seed 库生效（JDBC URL 门控）；serviceRepository.count()>0 跳过幂等（一体事务同生共灭单门控）。
 * 8 条服务逐字锚定前端 mock（stores/t2DataService.ts seed() L176-241）＋5 条权限申请逐字锚定
 * （mock L258-273，serviceId 关联 services[0]画像/[1]分群/[4]流失）。
 * 时间语义逐字：createdAt=daysAgo(80-i*5)；appliedAt=hoursAgo((i+2)*8)；
 * decidedAt=PENDING?null:hoursAgo((i+1)*6)；decidedBy=PENDING?null:'张数'。
 */
@Component
@Order(47)
public class T2B3DataInitializer implements ApplicationRunner {

    private final DataServiceRepository serviceRepository;
    private final DataServicePermissionRepository permissionRepository;
    private final ObjectMapper mapper;
    private final String datasourceUrl;

    public T2B3DataInitializer(DataServiceRepository serviceRepository,
                               DataServicePermissionRepository permissionRepository,
                               ObjectMapper mapper,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.serviceRepository = serviceRepository;
        this.permissionRepository = permissionRepository;
        this.mapper = mapper;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        if (serviceRepository.count() > 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<DataService> seeded = new ArrayList<>();

        seeded.add(seedService(now, 0, "客户 360 画像", DataService.TYPE_API,
                "/api/v1/customer/profile", "GET",
                "根据 customer_id 返回客户完整画像，含基础信息、消费汇总、标签列表、最近到店",
                "张数", DataService.STATUS_PUBLISHED, "v2.3", 4820, 86, "0.12",
                List.of("customer_id", "name_mask", "phone_mask", "level", "total_paid", "last_visit_at", "tags[]"),
                List.of("客户", "画像", "高频")));
        seeded.add(seedService(now, 1, "客户分群人群包", DataService.TYPE_API,
                "/api/v1/marketing/segment", "POST",
                "传入标签组合条件，返回符合条件的 customer_id 列表（分页），支持 M5 营销圈选",
                "李析", DataService.STATUS_PUBLISHED, "v1.5", 1240, 420, "0.45",
                List.of("segment_id", "customer_ids[]", "total_count", "expire_at"),
                List.of("营销", "分群", "标签")));
        seeded.add(seedService(now, 2, "经营日报数据集", DataService.TYPE_DATASET,
                null, null,
                "按门店、日期聚合的经营数据宽表（营业额/客流/转化/客单价），供 BI 工具直连",
                "王治", DataService.STATUS_PUBLISHED, "v1.0", 86, 2400, "0",
                List.of("store_id", "date", "revenue", "customer_count", "conversion_rate", "avg_ticket"),
                List.of("经营", "报表", "BI")));
        seeded.add(seedService(now, 3, "高价值客户名单", DataService.TYPE_DATASET,
                null, null,
                "累计消费 ≥ 50,000 元的客户名单，T+1 更新",
                "张数", DataService.STATUS_PUBLISHED, "v1.2", 32, 1800, "0",
                List.of("customer_id", "total_paid", "first_paid_at", "last_paid_at"),
                List.of("客户", "高价值")));
        seeded.add(seedService(now, 4, "流失预警推送", DataService.TYPE_API,
                "/api/v1/risk/churn/predict", "POST",
                "输入 customer_id 列表，返回每位客户的流失风险评分（0-100）和建议动作",
                "AI 模型组", DataService.STATUS_PUBLISHED, "v1.1", 2180, 156, "0.28",
                List.of("customer_id", "churn_score", "risk_level", "suggest_action"),
                List.of("AI", "流失预警", "风控")));
        seeded.add(seedService(now, 5, "项目消费明细", DataService.TYPE_API,
                "/api/v1/order/items", "GET",
                "订单项目明细查询，支持按门店、项目、时间范围筛选（草稿，待联调）",
                "李析", DataService.STATUS_DRAFT, "v0.1", 0, 0, "0",
                List.of("order_no", "item_id", "item_name", "quantity", "price", "paid_amount"),
                List.of("订单", "明细")));
        seeded.add(seedService(now, 6, "老版客户标签接口", DataService.TYPE_API,
                "/api/v0/customer/tags", "GET",
                "【已废弃】旧版客户标签查询接口，请使用 /api/v1/customer/profile 中的 tags 字段",
                "张数", DataService.STATUS_DEPRECATED, "v0.9", 42, 220, "1.2",
                List.of("customer_id", "tag_codes[]"),
                List.of("废弃", "标签")));
        seeded.add(seedService(now, 7, "门店业绩排行榜", DataService.TYPE_DATASET,
                null, null,
                "门店日/周/月业绩排名宽表，供经营驾驶舱和大屏使用",
                "王治", DataService.STATUS_PUBLISHED, "v1.0", 128, 980, "0",
                List.of("store_id", "period", "rank", "revenue", "target", "completion_rate"),
                List.of("门店", "业绩", "大屏")));

        DataService custSvc = seeded.get(0);
        DataService segSvc = seeded.get(1);
        DataService churnSvc = seeded.get(4);

        seedPerm(now, 0, segSvc, "营销-小赵",
                "618 大促人群圈选，需要调用分群接口筛选高价值客户",
                DataServicePermission.STATUS_PENDING);
        seedPerm(now, 1, churnSvc, "客服-小钱",
                "客服中心日常流失预警外呼，需要批量查询客户流失评分",
                DataServicePermission.STATUS_PENDING);
        seedPerm(now, 2, custSvc, "BI-小孙",
                "BI 看板客户明细 drill-down",
                DataServicePermission.STATUS_APPROVED);
        seedPerm(now, 3, segSvc, "短信运营-小李",
                "生日月客户触达活动",
                DataServicePermission.STATUS_APPROVED);
        seedPerm(now, 4, custSvc, "外部合作方",
                "需要全量客户数据做联合建模",
                DataServicePermission.STATUS_REJECTED);
    }

    /** 单条服务种子（时间语义逐字对齐 mock：createdAt=daysAgo(80-i*5)）。 */
    private DataService seedService(OffsetDateTime now, int i, String name, String type,
                                    String endpoint, String method, String description, String owner,
                                    String status, String version, int callCount24h, int avgLatency,
                                    String errorRate, List<String> fields, List<String> tags) {
        DataService s = new DataService();
        s.setName(name);
        s.setType(type);
        s.setEndpoint(endpoint);
        s.setMethod(method);
        s.setDescription(description);
        s.setOwner(owner);
        s.setStatus(status);
        s.setCallCount24h(callCount24h);
        s.setAvgLatency(avgLatency);
        s.setErrorRate(new BigDecimal(errorRate));
        s.setFields(writeJson(fields));
        s.setTags(writeJson(tags));
        s.setVersion(version);
        s.setCreatedAt(now.minusDays(80L - i * 5L));
        return serviceRepository.save(s);
    }

    /** 单条权限申请种子（时间语义逐字对齐 mock：appliedAt=hoursAgo((i+2)*8)；decidedAt/decidedBy 仅非 PENDING）。 */
    private void seedPerm(OffsetDateTime now, int i, DataService svc, String applicant,
                          String reason, String status) {
        DataServicePermission p = new DataServicePermission();
        p.setServiceId(svc.getId());
        p.setServiceName(svc.getName());
        p.setApplicant(applicant);
        p.setReason(reason);
        p.setStatus(status);
        p.setAppliedAt(now.minusHours((i + 2L) * 8L));
        if (!DataServicePermission.STATUS_PENDING.equals(status)) {
            p.setDecidedAt(now.minusHours((i + 1L) * 6L));
            p.setDecidedBy("张数");
        }
        permissionRepository.save(p);
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
