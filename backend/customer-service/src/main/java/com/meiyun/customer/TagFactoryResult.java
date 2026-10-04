package com.meiyun.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * T2-B2 标签工厂-计算结果集（棒⑥卡7 T2-03，表 tag_factory_result / V70）。
 * 发布时同事务整批重写（先删后插），仅存当前发布版本成员；集团级资产无 store_code 维度。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tag_factory_result")
public class TagFactoryResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false)
    private Long factoryId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "tag_version", nullable = false, length = 20)
    private String tagVersion;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;

    @PrePersist
    void prePersist() {
        if (computedAt == null) computedAt = OffsetDateTime.now();
    }
}
