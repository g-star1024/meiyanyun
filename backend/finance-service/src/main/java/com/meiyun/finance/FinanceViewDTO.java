package com.meiyun.finance;

import java.util.List;

/**
 * finance-service 只读聚合视图 DTO（读时聚合，不落库）。
 *
 * <p>数据源：txn-service 资金流水（订单收款 / 退款 / 卡扣次划扣）与 customer-service 会员卡余额，
 * 经服务间内部端点拉取后在本服务聚合。金额一律换算为「元」（数据源为 Long「分」，/100.0），
 * 门店码已解析为中文店名，科目/方向/来源等枚举对齐前端 financeCore / finReports 的活规格。
 *
 * <p>红线：本批全只读，无任何资金动词；内部端点以 system(GROUP) 全量返回，
 * 聚合后已按登录人门店域（DataScope.canReadStore）逐行收敛。
 */
public final class FinanceViewDTO {
    private FinanceViewDTO() {}

    /**
     * 台账流水（对齐前端 financeCore.LedgerEntry）。
     *  subject：RF-REVENUE 主营收入 / RF-REFUND 退款 / RF-DEPOSIT 预收；
     *  direction：IN 收入 / OUT 支出或预收转出；source：CASHIER 收银 / ERP 核销划扣；
     *  refType：ORDER 订单收款 / REFUND 退款 / WRITEOFF 卡扣次划扣；reconciled 对账标记；
     *  channel：真实收款渠道码（cash/card/wxpay/alipay/balance/transfer）；
     *  mixed：一单多渠道混合支付标记（channel 为主渠道——入账额最大一笔，前端展示「混合（主：xx）」）。
     */
    public record LedgerEntry(
            String id, String txnId, String date, String subject, String direction,
            Double amount, String channel, String source, String refType,
            String refNo, String store, String memo, Boolean reconciled, Boolean mixed) {}

    /**
     * 会员卡余额行（对齐前端 finReports.MemberCard）。
     *  type：STORED 储值卡 / TIMES 疗程卡（后端无独立卡类型字段，按 totalTimes>0 推断）；
     *  giftBalance 赠送金无数据源投影 0；lastConsumeAt 无消费流水，回落开卡时间；
     *  status：在用→NORMAL、退卡中→FROZEN、已退卡/已用完→DORMANT（沉睡展示口径）。
     */
    public record CardBalance(
            String id, String cardNo, String customerName, String type,
            Double balance, Double giftBalance, Integer timesTotal, Integer timesRemain,
            String lastConsumeAt, String status, String store) {}

    /** 卡余额聚合汇总（储值 / 赠送 / 疗程估值，单位元；疗程估值单价为前端活规格口径 2000 元/次）。 */
    public record CardBalanceBundle(List<CardBalance> cards, Double storedTotal,
                                    Double giftTotal, Double timesValueTotal) {}
}
