package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 客情咨询（M4-06）：过敏史采集 B0-1，隐私脱敏。
 */
@Entity
@Table(name = "consultation")
@Getter @Setter @NoArgsConstructor
public class Consultation {

    @Id
    @Column(name = "consult_id", length = 24)
    private String consultId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "allergy_history", columnDefinition = "TEXT")
    private String allergyHistory;

    @Column(name = "drug_allergy", columnDefinition = "TEXT")
    private String drugAllergy;

    @Column(name = "scar_constitution", nullable = false, length = 4)
    private String scarConstitution;

    @Column(nullable = false, length = 4)
    private String pregnancy;

    @Column(name = "coagulation_abn", nullable = false, length = 4)
    private String coagulationAbn;

    @Column(name = "skin_status", length = 32)
    private String skinStatus;

    @Column(columnDefinition = "TEXT")
    private String needs;

    @Column(length = 32)
    private String consultant;

    @Column(name = "privacy_masked", nullable = false)
    private Boolean privacyMasked;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (privacyMasked == null) privacyMasked = true;
        if (scarConstitution == null) scarConstitution = "否";
        if (pregnancy == null) pregnancy = "否";
        if (coagulationAbn == null) coagulationAbn = "否";
    }
}
