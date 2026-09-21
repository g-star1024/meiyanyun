package com.meiyun.customer;

import java.time.Instant;

/**
 * B83 卡1 L66 疗程跟踪聚合读 DTO（对齐前端 mock store asset.ts 的 TimesAsset + AssetTxn 结构）。
 *
 * <p>不分页，全量返回当前门店在用 / 已用完 / 已退卡 COURSE 卡。金额单位「分」，
 * 前端适配层做 /100 换算；卡状态中文存储中文展示（在用/已用完/已退卡/退卡中），不转英文码。
 *
 * <p>trackStatus 由后端按 30 天阈值推导（对齐前端 EXPIRING_DAYS=30）：
 * <ul>
 *   <li>已退卡 / 退卡中 → FROZEN</li>
 *   <li>已用完 / 在用且已过期未用完 → FINISHED</li>
 *   <li>在用且 0 &lt; daysLeft ≤ 30 → EXPIRING</li>
 *   <li>在用且 expiresAt 为空或 daysLeft &gt; 30 → ACTIVE</li>
 * </ul>
 */
public record CourseTrackDTO(
        String cardNo,
        String customerId,
        String customerName,
        String phoneMask,
        String cardItem,
        Integer totalTimes,
        Integer remainTimes,
        Integer usedTimes,
        Long balance,
        String status,
        Instant expiresAt,
        Long daysLeft,
        String trackStatus) {}
