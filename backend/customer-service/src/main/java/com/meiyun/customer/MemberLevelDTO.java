package com.meiyun.customer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会员等级读模型：对齐前端 stores/level.ts 的 MemberLevel 结构。
 * id 取等级中文短名（普通/银卡/金卡/钻石/黑卡，customer.level 同契约）；
 * name 为展示名（加「会员」后缀）；memberCount/memberPercent 读时实时 count customer 计算，不读历史 cnt 假数据；
 * upgradeCondition 由阈值派生（阈值 0=注册即享，&gt;0=累计消费 ≥ ¥x,xxx）。
 */
public record MemberLevelDTO(
        String id,
        String tier,
        String name,
        String color,
        BigDecimal upgradeThreshold,
        String upgradeCondition,
        List<String> benefits,
        long memberCount,
        long memberPercent,
        boolean isTop,
        BigDecimal discount
) {
}
