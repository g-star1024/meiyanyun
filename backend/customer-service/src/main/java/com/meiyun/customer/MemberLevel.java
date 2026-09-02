package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 会员五级聚合统计表（ID-5：29160+11664+5346+1944+486=48600）。
 * 此为聚合口径表；客户明细在 customer 表。
 */
@Entity
@Table(name = "member_level")
@Getter @Setter @NoArgsConstructor
public class MemberLevel {

    @Id
    @Column(length = 8)
    private String level;                 // 普通/银卡/金卡/钻石/黑卡

    @Column(nullable = false)
    private Integer cnt;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal discount;
}
