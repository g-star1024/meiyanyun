package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3 客户域设置单例（M3-B1 / DESIGN-M3 §3 M3-18）。
 *
 * <p>全表仅 id=1 一行（V53 CHECK id = 1），settings JSONB 存全量配置键：
 * UI 五组 20 键（脱敏/等级/标签/隐私/跟进）＋后端专用键（如 npsReachCount，
 * NPS 回收率口径供数，UI 不绑定）。读写整体替换，缺省值合并由
 * {@link M3SettingsService} 负责。
 *
 * <p>桥接约定（DESIGN L80 硬编码改读配置）：保存时由服务层同步
 * {@link LevelRuleConfig}（m3_settings 为主、level_rule_config 为从）。
 */
@Entity
@Table(name = "m3_settings")
@Getter @Setter @NoArgsConstructor
public class M3Settings {

    /** 单例行主键：恒为 1（V53 CHECK id = 1）。 */
    @Id
    @Column(name = "id")
    private Integer id;

    /** 设置全量 JSON：五组 20 个 UI 键＋后端专用键。 */
    @Convert(converter = MapJsonConverter.class)
    @Column(name = "settings", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> settings = new LinkedHashMap<>();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    @PrePersist
    void prePersist() {
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
        if (updatedBy == null || updatedBy.isBlank()) updatedBy = "system";
        if (settings == null) settings = new LinkedHashMap<>();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
