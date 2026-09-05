package com.meiyun.finance;

/**
 * 资金分录入账命令（服务间内部端点入参，经营域 txn 投递）。
 *
 * <p>字段口径：金额 amount 为 Long「分」且始终为正，方向由 direction 表达；
 * channel 为前端渠道码（cash/card/wxpay/alipay/balance/transfer），内部结转发 null；
 * idemKey 为经营域事件维度幂等键（如 REFUND:RFxxx / ORDER:ODxxx:REVENUE / WRITEOFF:WOxxx:DEPOSIT），
 * 缺失时由落账服务按 bizType:bizRef:subject:direction 兜底生成。
 */
public record FundEntryCmd(
        String idemKey,
        String bizRef,
        String bizType,
        String subject,
        String direction,
        Long amount,
        String channel,
        String source,
        String refType,
        String storeCode,
        String memo,
        String occurredAt
) {
}
