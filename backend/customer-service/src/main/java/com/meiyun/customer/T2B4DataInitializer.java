package com.meiyun.customer;

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
 * T2-B4 数据源演示种子（@Order(48)，T2-B1 用 45、T2-B2 用 46、T2-B3 用 47 顺链）。
 * 仅 meiyun_seed 库生效（JDBC URL 门控）；repository.count()>0 跳过幂等（一体事务同生共灭单门控）。
 * 三条种子覆盖类型三值 CDC/KAFKA/THIRD_PARTY 各一，状态一律 REGISTERED·lastSyncAt=null
 * （注册登记；棒⑧卡5 起三类型探测真实化——CDC_CORE_DB 指向 pg 容器可探测通过，
 * KAFKA_TOUCH 无 broker 如实 400；装配/消费运行时归数据中台二期 DESIGN-T3 §7）。
 * 时间语义：createdAt=now-30d/-37d/-44d（逐条隔 7 天，照 T2-B3 阶梯先例）。
 */
@Component
@Order(48)
public class T2B4DataInitializer implements ApplicationRunner {

    private final DataSourceRepository repository;
    private final String datasourceUrl;

    public T2B4DataInitializer(DataSourceRepository repository,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.repository = repository;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<DataSource> seeded = new ArrayList<>();

        seeded.add(seed(now, 0, "CDC_CORE_DB", "核心库 CDC（PostgreSQL）", DataSource.TYPE_CDC,
                "pg://meiyun-pg:5432/meiyun_core",
                "业务核心库 customer/txn_order 全量 CDC 接入登记（装配/消费运行时归数据中台二期）", "王治"));
        seeded.add(seed(now, 1, "KAFKA_TOUCH", "触点时间线 Kafka", DataSource.TYPE_KAFKA,
                "kafka://meiyun-kafka:9092/touch-events",
                "五通道触点时间线 touch_event 接入登记（装配/消费运行时归数据中台二期）", "李析"));
        seeded.add(seed(now, 2, "TP_MEITUAN", "美团三方回传 API", DataSource.TYPE_THIRD_PARTY,
                "https://open-api.meituan.com/v1/returnback",
                "美团渠道核销/评价回传三方 API 登记", "张数"));

        repository.saveAll(seeded);
    }

    private DataSource seed(OffsetDateTime now, int i, String code, String name, String type,
                            String endpoint, String description, String owner) {
        DataSource d = new DataSource();
        d.setCode(code);
        d.setName(name);
        d.setType(type);
        d.setEndpoint(endpoint);
        d.setDescription(description);
        d.setOwner(owner);
        d.setStatus(DataSource.STATUS_REGISTERED);
        d.setCreatedAt(now.minusDays(30L + i * 7L));
        d.setUpdatedAt(now.minusDays(30L + i * 7L));
        return d;
    }
}
