package com.meiyun.customer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会员等级目录（一线开单岗可读，B62 卡2）：对齐前端 stores/level.ts 的等级展示字段，
 * 但不含 memberCount/memberPercent 等经营统计（那些归 {@link MemberLevelDTO} + level:view 管理端读模型）。
 * id 取等级中文短名（普通/银卡/金卡/钻石/黑卡，customer.level 同契约）；name 为展示名（加「会员」后缀）；
 * upgradeCondition 由阈值派生；discount 为等级项目结算折扣率（1.00=不折），前端仅预估，开单以后端实算为准。
 */
public record LevelCatalogDTO(
        String id,
        String tier,
        String name,
        String color,
        BigDecimal upgradeThreshold,
        String upgradeCondition,
        List<String> benefits,
        boolean isTop,
        BigDecimal discount,
        Integer freeCareTimes
) {
}
