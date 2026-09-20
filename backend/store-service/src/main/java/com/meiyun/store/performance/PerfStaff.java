package com.meiyun.store.performance;

import com.meiyun.store.daily.IntListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "perf_staff",
        uniqueConstraints = @UniqueConstraint(name = "uk_pf_period", columnNames = {"period", "store_code", "name"}),
        indexes = {
                @Index(name = "idx_pf_store", columnList = "store_code"),
                @Index(name = "idx_pf_period", columnList = "period"),
                @Index(name = "idx_pf_role", columnList = "role")
        })
@Getter
@Setter
@NoArgsConstructor
public class PerfStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period", nullable = false, length = 8)
    private String period;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "name", nullable = false, length = 32)
    private String name;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "title", nullable = false, length = 32)
    private String title;

    @Column(name = "avatar_letter", nullable = false, length = 4)
    private String avatarLetter;

    @Column(name = "target_fen", nullable = false)
    private Long targetFen = 0L;

    @Column(name = "actual_fen", nullable = false)
    private Long actualFen = 0L;

    @Column(name = "orders", nullable = false)
    private Integer orders = 0;

    @Column(name = "commission_bp", nullable = false)
    private Integer commissionBp = 0;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "joined_at", nullable = false)
    private LocalDate joinedAt;

    @Convert(converter = IntListJsonConverter.class)
    @Column(name = "trend_json", columnDefinition = "jsonb", nullable = false)
    private List<Integer> trend = List.of();

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (targetFen == null) targetFen = 0L;
        if (actualFen == null) actualFen = 0L;
        if (orders == null) orders = 0;
        if (commissionBp == null) commissionBp = 0;
        if (trend == null) trend = List.of();
    }
}
