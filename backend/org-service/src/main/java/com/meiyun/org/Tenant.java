package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 租户（单品牌种子「美颜·」，Phase 3 扩展多品牌）。 */
@Entity
@Table(name = "tenant")
@Getter @Setter @NoArgsConstructor
public class Tenant {

    @Id
    @Column(name = "tenant_id", length = 16)
    private String tenantId;

    @Column(name = "tenant_name", nullable = false, length = 64)
    private String tenantName;

    @Column(nullable = false, length = 32)
    private String brand;

    @Column(nullable = false, length = 8)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
