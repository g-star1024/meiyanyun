package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3 设置变更日志（M3-B1）：每次保存/复位追加一行，
 * action 人类可读描述（如「沉睡阈值调整为 90 天」），payload 存变更前后差异快照；
 * M3-18 变更记录卡直读（按 created_at 倒序取近 50 条）。
 */
@Entity
@Table(name = "m3_settings_change_log")
@Getter @Setter @NoArgsConstructor
public class M3SettingsChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 变更动作描述（人类可读，展示于 M3-18 变更记录卡）。 */
    @Column(name = "action", nullable = false, length = 128)
    private String action;

    /** 操作者（DataScope.currentActor()）。 */
    @Column(name = "actor", nullable = false, length = 64)
    private String actor;

    /** 变更快照 JSON：{before:{...}, after:{...}} 仅含差异键。 */
    @Convert(converter = MapJsonConverter.class)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (actor == null || actor.isBlank()) actor = "system";
        if (payload == null) payload = new LinkedHashMap<>();
    }
}
