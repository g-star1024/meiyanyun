package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会员等级配置表（五级：普通/银卡/金卡/钻石/黑卡）。
 * level 为中文短名主键，被 customer.level 物理外键引用，主键值不可改/不可删。
 * cnt 为 ID-5 历史聚合口径列（29160+11664+5346+1944+486=48600，与真实客户数无关），保留不暴露给新读模型；
 * 当前等级人数一律读时实时 count(customer) group by level（派生统计不入库）。
 */
@Entity
@Table(name = "member_level")
@Getter @Setter @NoArgsConstructor
public class MemberLevel {

    @Id
    @Column(length = 8)
    private String level;                 // 普通/银卡/金卡/钻石/黑卡

    /** ID-5 历史聚合口径人数（假数据，仅兼容保留；新业务一律实时 count customer）。 */
    @Column(nullable = false)
    private Integer cnt;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal discount;

    /** 等级英文码（NORMAL/SILVER/GOLD/DIAMOND/BLACK），前端 tier 契约与配色依据。 */
    @Column(length = 16)
    private String tier;

    /** 等级排序（1~5，从小到大），读模型按此升序。 */
    @Column(name = "sort_no")
    private Integer sortNo;

    /** 升级累计消费门槛（元）；普通为 0（注册即享）。 */
    @Column(name = "upgrade_threshold", precision = 12, scale = 2)
    private BigDecimal upgradeThreshold;

    /** 权益清单（JSON 数组文本落库，如 ["项目折扣 9.5 折","生日当月 1.2 倍积分"]）。 */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "benefits", columnDefinition = "text")
    private List<String> benefits;

    /** 等级主色（十六进制，用于卡片左边框/圆点/升级条件底色）。 */
    @Column(length = 16)
    private String color;

    /** 是否最高等级（黑卡 true，卡片展示「最高级」标）。 */
    @Column(name = "is_top")
    private Boolean isTop;
}
