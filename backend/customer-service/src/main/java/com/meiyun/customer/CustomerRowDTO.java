package com.meiyun.customer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 客户列表行 DTO：包含列表页真用字段 + 标签（从标签关系批量解析）。
 * 与详情页 360 富画像解耦——列表只取轻量字段，detail 才逐块补 RFM/偏好/对比照等。
 */
public record CustomerRowDTO(
        String customerId,
        String name,
        String phone,
        String gender,
        String level,
        String storeCode,
        String storeName,
        String channel,
        BigDecimal totalSpend,
        Integer visitCount,
        String ownerStaffId,
        String ownerStaffName,
        String status,
        Long points,
        List<String> tags
) {
}
