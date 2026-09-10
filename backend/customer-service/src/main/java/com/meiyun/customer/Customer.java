package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "customer")
@Getter @Setter @NoArgsConstructor
public class Customer {

    @Id
    @Column(name = "customer_id", length = 16)
    private String customerId;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false, length = 16)
    private String phone;

    @Column(nullable = false, length = 4)
    private String gender;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(nullable = false, length = 8)
    private String level;                     // 会员等级（中文，全站统一契约）：普通/银卡/金卡/钻石/黑卡

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(name = "channel", length = 16)
    private String channel;                   // 获客渠道：WALK_IN/REFERRAL/WECHAT/DOUYIN/XIAOHONGSHU/MEITUAN/OTHER

    @Column(name = "total_spend", precision = 12, scale = 2)
    private java.math.BigDecimal totalSpend;  // 累计消费（元）

    @Column(name = "visit_count")
    private Integer visitCount;               // 到店次数

    @Column(name = "owner_staff_id", length = 16)
    private String ownerStaffId;              // 归属人（空=公海）

    // ---- 客情登记扩展字段（P5-B28 ROADMAP 282：M4-06 登记页扩展项后端化，仅建档写入、档案 tab 回显） ----

    /** 年龄（选填，1~150；无 birthDate 时的口径，老客户为空）。 */
    @Column
    private Integer age;

    /** 肤质类型：干性/油性/混合性/敏感性/中性（中文枚举，库内即中文）。 */
    @Column(name = "skin_type", length = 8)
    private String skinType;

    /** 主要诉求清单（JSON 数组落库，如 ["痤疮","补水"]）。 */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "concerns", columnDefinition = "text")
    private List<String> concerns;

    /** 是否确认无过敏史（与 allergies 互斥：true 时 allergies 必为空数组；存量老客户列为空，读出按 false 处理）。 */
    @Column(name = "allergy_none")
    private Boolean allergyNone = false;

    /** 过敏史阳性项清单（JSON 数组，如 ["药物","麻醉药"]；allergyNone=true 时必为空）。 */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "allergies", columnDefinition = "text")
    private List<String> allergies;

    /** 过敏/病史补充（自由文本 ≤512 字，如具体药物名称、既往病史）。 */
    @Column(name = "allergy_note", length = 512)
    private String allergyNote;

    /** 意向项目清单（JSON 数组，如 ["光子嫩肤","水光针"]）。 */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "intent_projects", columnDefinition = "text")
    private List<String> intentProjects;

    /** 意向程度：高/中/低（中文枚举，空=未评估）。 */
    @Column(name = "intent_level", length = 4)
    private String intentLevel;

    /** 预算区间：3千以下/3千-1万/1万-3万/3万以上（中文枚举，空=未填）。 */
    @Column(length = 16)
    private String budget;

    /** 沟通要点（自由文本 ≤512 字：客户关注/顾虑/约定跟进事项）。 */
    @Column(name = "intent_note", length = 512)
    private String intentNote;

    @Column(nullable = false)
    private Long points;

    @Column(nullable = false, length = 8)
    private String status;                    // 活跃/沉睡/流失

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (points == null) points = 0L;
        if (allergyNone == null) allergyNone = false;
        if (status == null) status = "活跃";
        if (totalSpend == null) totalSpend = java.math.BigDecimal.ZERO;
        if (visitCount == null) visitCount = 0;
    }

    public Boolean getAllergyNone() {
        return allergyNone == null ? Boolean.FALSE : allergyNone;
    }
}
