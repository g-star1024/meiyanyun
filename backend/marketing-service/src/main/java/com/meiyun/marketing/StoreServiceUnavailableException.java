package com.meiyun.marketing;

/**
 * store-service 门店主数据暂时不可用（连接失败/超时/4xx/5xx）。
 *
 * <p>与「门店编码真实不存在」严格分层：前者是依赖方故障，写链路应转 503 提示稍后重试，
 * 不得降级成 400 业务错误误导用户；后者由 name-map 200 响应中缺码表达，仍是 400。
 */
public class StoreServiceUnavailableException extends RuntimeException {

    public StoreServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
