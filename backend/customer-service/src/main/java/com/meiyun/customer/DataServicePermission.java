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
 * T2-B3 数据服务权限申请（DESIGN-T2 T2-04，表 data_service_permission / V69）。
 * service_id 逻辑引用 data_service.id，零物理外键（V63-V66 先例）；
 * service_name 申请时冗余快照（服务改名/下线后申请单仍可读）。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_service_permission")
public class DataServicePermission {

    /** 状态三值。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "service_name", nullable = false, length = 100)
    private String serviceName;

    @Column(name = "applicant", nullable = false, length = 50)
    private String applicant;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @PrePersist
    void prePersist() {
        if (reason == null) reason = "";
        if (status == null) status = STATUS_PENDING;
        if (appliedAt == null) appliedAt = OffsetDateTime.now();
    }
}
