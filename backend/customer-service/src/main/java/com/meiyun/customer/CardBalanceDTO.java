package com.meiyun.customer;

import java.time.OffsetDateTime;

/**
 * finance-service 卡余额聚合专用：会员卡只读投影（服务间内部端点返回）。
 *
 * <p>金额 balance/giftBalance 为 Long「分」；客户姓名已在客户域解析（不把姓名解析责任外推给财务域）。
 * B16 售卡开卡后补齐溯源字段：productCode（CD-/CS- 模板编码）、cardType（CARD/COURSE 快照，
 * 历史导入卡为空，由 totalTimes 推断）、expiresAt（有效期截止，历史卡为空）、giftBalance（赠送金，
 * 无赠送为 0）；lastConsumeAt 仍回落为开卡时间——由调用方按需降级展示。
 */
public record CardBalanceDTO(
        String cardNo,
        String customerId,
        String customerName,
        String cardItem,
        String storeCode,
        Integer totalTimes,
        Integer remainTimes,
        Long balance,
        Long giftBalance,
        String status,
        String productCode,
        String cardType,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {
}
