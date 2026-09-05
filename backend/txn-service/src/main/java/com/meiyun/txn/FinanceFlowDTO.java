package com.meiyun.txn;

import java.util.List;

/**
 * finance-service 读时聚合专用：交易域资金数据批量投影（服务间内部端点返回）。
 *
 * <p>仅投影财务台账聚合所需的最小字段；金额一律为 Long「分」，时间为 OffsetDateTime ISO，
 * 由调用方 finance-service 换算「元」、解析门店码→店名、映射 RF 科目。
 * 四类资金来源：订单收款（txn_order 已收款）、退款（txn_refund 已退款）、划扣核销（writeoff_record DONE）、退卡（txn_card_cancel REFUNDED）。
 */
public final class FinanceFlowDTO {

    private FinanceFlowDTO() {
    }

    /**
     * 订单收款流水（status=已收款）。
     * payMethod：该单收款渠道（cash/card/wxpay/alipay/balance）；混合支付取 postedAmount 最大一笔的渠道，
     * mixed=true 标记一单多渠道（口径：最大笔渠道 + 混合标记，前端展示「混合（主：xx）」）；
     * 无支付流水（历史数据/异常）时 payMethod 为 null。
     */
    public record OrderFlow(
            String orderNo,
            String storeCode,
            String customerId,
            String project,
            Long amount,
            String status,
            java.time.OffsetDateTime createdAt,
            String payMethod,
            Boolean mixed
    ) {
    }

    /** 退款流水（status=REFUNDED 已退款）。 */
    public record RefundFlow(
            String txnNo,
            String orderNo,
            String storeCode,
            String customerName,
            String channel,
            Long refundAmt,
            Long fee,
            String status,
            java.time.OffsetDateTime createdAt
    ) {
    }

    /** 划扣核销流水（status=DONE）。 */
    public record WriteoffFlow(
            String writeoffId,
            String orderNo,
            String cardNo,
            String storeCode,
            String project,
            Integer timesUsed,
            Long amount,
            String status,
            java.time.OffsetDateTime createdAt
    ) {
    }

    /**
     * 退卡流水（status=REFUNDED 财务终审完成）。
     * 资金恒等式 balance = refundAmt + fee：refundAmt 为实退客户（现金/转账流出），
     * balance 全额冲减预收，fee 违约金转收入；channel：ORIGINAL / CASH / TRANSFER（退卡无原单号，ORIGINAL 保守留空）。
     */
    public record CardCancelFlow(
            String txnNo,
            String cardNo,
            String storeCode,
            String customerName,
            String channel,
            Long balance,
            Long refundAmt,
            Long fee,
            String status,
            java.time.OffsetDateTime createdAt
    ) {
    }

    /** 复合返回：一次调用取回四类资金流水（订单/退款/划扣/退卡）。 */
    public record Bundle(
            List<OrderFlow> orders,
            List<RefundFlow> refunds,
            List<WriteoffFlow> writeoffs,
            List<CardCancelFlow> cardCancels
    ) {
    }
}
