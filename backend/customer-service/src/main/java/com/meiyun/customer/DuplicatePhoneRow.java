package com.meiyun.customer;

import java.time.Instant;

/**
 * 撞单候选扫描原生查询投影：归一化手机号重复组内的单个有效客户行（轻量列）。
 * 别名与 SQL 中 as 列名一一对应（Spring Data interface projection）。
 */
public interface DuplicatePhoneRow {
    String getCustomerId();
    String getName();
    String getPhone();
    String getLevel();
    String getStoreCode();
    String getOwnerStaffId();
    Instant getCreatedAt();
}
