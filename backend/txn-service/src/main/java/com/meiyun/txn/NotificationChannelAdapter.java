package com.meiyun.txn;

/**
 * 通知渠道适配器接口（域⑦ 多渠道扇出）。各渠道独立实现，扇出 Job 按 channel 码路由。
 *
 * <p>设计铁律：真实运营商网关（短信/企微/邮件）仅留「可配置 dev 网关」接入位——把消息 POST 到
 * 配置的 webhook，不引入任何运营商凭证/SDK。未配置网关时返回 SKIPPED（诚实标注，绝不伪造发送成功）。
 */
public interface NotificationChannelAdapter {

    /** 是否处理该渠道码。 */
    boolean supports(String channel);

    /** 执行一次投递，返回终态结果。 */
    DeliveryResult send(Notification notification, String channel, NotifyPreference preference);

    record DeliveryResult(String status, String errorMessage) {
        public static DeliveryResult sent() {
            return new DeliveryResult("SENT", null);
        }

        public static DeliveryResult skipped(String msg) {
            return new DeliveryResult("SKIPPED", msg);
        }

        public static DeliveryResult failed(String msg) {
            return new DeliveryResult("FAILED", msg);
        }

        /** 确定性拒绝（如网关 4xx）：重试无意义，直接 DEAD 留待人工。 */
        public static DeliveryResult dead(String msg) {
            return new DeliveryResult("DEAD", msg);
        }

        public static DeliveryResult deferred(String msg) {
            return new DeliveryResult("DEFERRED", msg);
        }
    }
}
