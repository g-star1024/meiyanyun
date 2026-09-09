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

    /**
     * 单卡流水行（B24 卡2，对齐前端 finReports.CardTxn）。
     *  kind：RECHARGE 充值 / CONSUME 消费划扣 / REFUND 退款回加 / ADJUST 调整（customer 侧无 FREEZE，
     *  冻结即 amount=0 的 ADJUST，由前端适配层映射）；金额/赠金单位「元」，纯扣次 amount=0；
     *  refNo=bizRef（RC/OD/WO/RF/CC 单号前缀），orderNo 为关联订单（可空）。
     */
    public record CardTxn(
            Long ledgerId, String kind, Double amount, Double balanceAfter,
            Double giftAmount, Double giftAfter, String refNo, String orderNo,
            String operator, String date) {}

    /**
     * 单卡时间线（B24 卡2）：卡快照（客户/卡项/类型/产品/本金赠金/次数/中文状态）+ 账龄正序流水。
     * 数据源 customer-service /internal/cards/{cardNo}/ledger（Long 分 → 元、门店码已解析店名）。
     */
    public record CardTimeline(
            String cardNo, String customerId, String customerName, String cardItem,
            String storeCode, String store, String cardType, String productCode, String type,
            Double balance, Double giftBalance, Integer timesTotal, Integer timesRemain,
            String status, List<CardTxn> txns) {}

    /**
     * 核销双签明细行（B24 卡2，对齐前端财务核销明细页）。
     *  数据源 txn-service /internal/writeoff-details（不固化 status，全状态）：
     *  status DONE 已核销/ABNORMAL 异常/VOID 已作废；cardNo 空为订单整单核销（双签字段可空）；
     *  sign1 操作人、sign2 复核人（「工号 姓名」）、timesUsed 扣次次数、abnormalReason 异常/作废原因。
     */
    public record WriteoffDetail(
            String writeoffId, String orderNo, String cardNo, String storeCode, String store,
            String customerId, String customerName, String project,
            Integer timesUsed, Double amount, String status, String operator,
            String sign1, String sign2, String abnormalReason, String date) {}
}
