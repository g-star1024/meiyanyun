package com.meiyun.customer;

/**
 * 标签读模型：标签定义 + 覆盖客户数（customer_tag_rel group by 聚合，不暴露关系实体）。
 * 标签颜色/自动化规则在数据模型中不存在：颜色由前端按五分类固定映射，自动化规则归 Backlog。
 */
public record TagStatDTO(
        String tagId,
        String tagName,
        String category,
        long customerCount
) {
}
