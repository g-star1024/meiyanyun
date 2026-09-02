package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 预约明细（M4-01）。status 四态：已预约/已到店/未到诊/已取消。
 * 月度权威口径仍是 appointment_month（ID-7：1286=1112+174）。
 */
@Entity
@Table(name = "appointment")
@Getter @Setter @NoArgsConstructor
public class Appointment {

    @Id
    @Column(name = "appt_no", length = 24)
    private String apptNo;

    @Column(name = "customer_id", length = 16)
    private String customerId;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(nullable = false, length = 64)
    private String project;

    @Column(name = "appt_date", nullable = false)
    private LocalDate apptDate;

    @Column(name = "appt_time", nullable = false, length = 8)
    private String apptTime;

    @Column(length = 32)
    private String doctor;

    @Column(nullable = false, length = 16)
    private String source;                    // B端登记/C端小程序/C端App

    @Column(nullable = false, length = 8)
    private String status;                    // 已预约/已到店/未到诊/已取消

    @Column(name = "arrived_at")
    private OffsetDateTime arrivedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (source == null) source = "B端登记";
        if (status == null) status = "已预约";
    }
}
