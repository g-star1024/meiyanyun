package com.meiyun.store.health;

import jakarta.persistence.Column;
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

/**
 * 健康度六维分数行（B49 卡10）：dimension 六枚举 SAFETY/SERVICE/FINANCE/COMPLIANCE/STAFF/EQUIPMENT，
 * score 0-100，weight 权重（SAFETY/COMPLIANCE=2 余 1，照 mock）。
 */
@Entity
@Table(name = "health_score", uniqueConstraints = {
        @UniqueConstraint(name = "uk_health_score_store_dim", columnNames = {"storeCode", "dimension"})
}, indexes = {
        @Index(name = "idx_health_score_store", columnList = "storeCode")
})
@Getter
@Setter
@NoArgsConstructor
public class HealthScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String storeCode;

    @Column(nullable = false, length = 16)
    private String dimension;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private int weight;
}
