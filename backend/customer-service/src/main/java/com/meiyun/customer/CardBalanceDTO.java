package com.meiyun.customer;

import java.time.OffsetDateTime;

/**
 * finance-service 卡余额聚合专用：会员卡只读投影（服务间内部端点返回）。
 *
 * <p>金额 balance 为 Long「分」；客户姓名已在客户域解析（不把姓名解析责任外推给财务域）。
 * 后端无「赠送金 / 卡类型 / 最近消费」独立字段：类型由 totalTimes 推断（>0 疗程卡，否则储值卡），
 * giftBalance 无数据源投影为 0，lastConsumeAt 回落为开卡时间——由调用方按需降级展示。
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
        String status,
        OffsetDateTime createdAt
) {
}
