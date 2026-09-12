package com.meiyun.marketing;

/**
 * txn-service 交易只读投影暂时不可用（连接失败/超时/4xx/5xx）。
 *
 * <p>消费满额自动发赠金 Job 的唯一取数出口故障语义：扫描轮捕获本异常后不推进游标，
 * 下一轮按原窗口自愈重试（与 customer 域 AutoPointsService 同哲学），
 * 不得把基础设施故障误当成「本轮无已收款订单」而吞掉。
 */
public class TxnServiceUnavailableException extends RuntimeException {

    public TxnServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
