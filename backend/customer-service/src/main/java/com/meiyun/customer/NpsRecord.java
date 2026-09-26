package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * NPS 回访记录（M3-B1 / DESIGN-M3 §3 M3-12）。
 *
 * <p>问卷回执＋跟进状态；提交幂等 (customer_id, period) 部分唯一索引（V54），
 * 同一客户同一 ISO 周重复提交由服务层先查后插＋捕唯一约束兜底返 dedup=true。
 *
 * <p>category 不由前端入参决定：服务层按 score 推导（≥9 PROMOTER / ≥7 PASSIVE / else DETRACTOR），
 * 与前端 {@code categoryOf} 同规则；period 存 ISO 周 'YYYY-Wnn'，趋势按周聚合近 6 周。
 */
@Entity
@Table(name = "nps_record")
@Getter @Setter @NoArgsConstructor
public class NpsRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 记录单号：NR 前缀序号递增（NR%04d，照 TA%03d 先例）。 */
    @Column(name = "record_no", nullable = false, length = 24)
    private String recordNo;

    /** 客户号（逻辑引用 customer.customer_id 如 M001，可空）：匿名/未匹配为 NULL。 */
    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 客户姓名冗余快照：未匹配 customer 时仍可展示。 */
    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName = "";

    /** NPS 打分 0-10。 */
    @Column(name = "score", nullable = false)
    private Integer score;

    /** 分类三值：PROMOTER / PASSIVE / DETRACTOR（服务层按 score 推导）。 */
    @Column(name = "category", nullable = false, length = 16)
    private String category;

    /** 回访项目（如 热玛吉紧致/水光补水）。 */
    @Column(name = "service", nullable = false, length = 128)
    private String service = "";

    /** 评价标签 JSON 数组（M3-12 列表行展示前 3）。 */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
    private List<String> tags = List.of();

    /** 客户评语文本。 */
    @Column(name = "comment", nullable = false, columnDefinition = "text")
    private String comment = "";

    /** 提交周期 ISO 周 'YYYY-Wnn'：与 customer_id 组提交幂等；趋势桶按周聚合。 */
    @Column(name = "period", nullable = false, length = 16)
    private String period;

    /** 跟进状态：PENDING 待跟进 / FOLLOWED 已跟进。 */
    @Column(name = "follow_status", nullable = false, length = 16)
    private String followStatus = "PENDING";

    /** 跟进备注（标记已跟进时填）。 */
    @Column(name = "follow_note", columnDefinition = "text")
    private String followNote;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (customerName == null) customerName = "";
        if (service == null) service = "";
        if (tags == null) tags = List.of();
        if (comment == null) comment = "";
        if (followStatus == null) followStatus = "PENDING";
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
