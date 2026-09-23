package com.meiyun.marketing;

/**
 * customer 域服务暂不可用（连接/超时/HTTP 故障）：
 * Flow 引擎按「该规则本轮软降级跳过、落 FAILED log、下轮自愈」处理（沿 TxnServiceUnavailableException 范式）。
 */
public class CustomerServiceUnavailableException extends RuntimeException {
    public CustomerServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
