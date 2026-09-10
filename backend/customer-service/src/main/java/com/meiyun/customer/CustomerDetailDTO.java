package com.meiyun.customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 客户详情 DTO：基础档案字段 + 归属员工/门店中文名（只读解析冗余，界面直接显中文）。
 * 字段与 Customer 实体一一对应，另加 ownerStaffName / storeName 两个展示名。
 * 尾部十项为 P5-B28（ROADMAP 282）客情登记扩展字段，建档写入、档案 tab 有值才显示。
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
        OffsetDateTime createdAt,
        Integer age,
        String skinType,
        List<String> concerns,
        Boolean allergyNone,
        List<String> allergies,
        String allergyNote,
        List<String> intentProjects,
        String intentLevel,
        String budget,
        String intentNote
) {
}
