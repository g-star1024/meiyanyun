package com.meiyun.customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 客户详情 DTO：基础档案字段 + 归属员工/门店中文名（只读解析冗余，界面直接显中文）。
 * 字段与 Customer 实体一一对应，另加 ownerStaffName / storeName 两个展示名。
 */
public record CustomerDetailDTO(
        String customerId,
        String name,
        String phone,
        String gender,
        LocalDate birthDate,
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
        OffsetDateTime createdAt
) {
}
