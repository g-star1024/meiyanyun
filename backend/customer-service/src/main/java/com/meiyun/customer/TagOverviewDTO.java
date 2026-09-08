package com.meiyun.customer;

/**
 * 标签覆盖汇总读模型（标签管理页 KPI/统计卡）：
 * totalTags 标签总数；coveredCustomers 至少打过一个标签的去重客户数；
 * totalAssignments 累计打标人次（customer_tag_rel 行数）；avgTagsPerCustomer 已打标客户人均标签数。
 * 关系表无时间戳列，不提供"今日新增打标"口径（自动化规则/按时间统计归事件流 Backlog）。
 */
public record TagOverviewDTO(
        long totalTags,
        long coveredCustomers,
        long totalAssignments,
        double avgTagsPerCustomer
) {
}
