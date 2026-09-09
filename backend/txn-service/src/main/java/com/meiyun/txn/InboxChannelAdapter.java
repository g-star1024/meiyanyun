package com.meiyun.txn;

import org.springframework.stereotype.Component;

/**
 * 站内信渠道适配器：notification 表行本身就是 INBOX 载体，无需外发，直接视为已送达。
 */
@Component
public class InboxChannelAdapter implements NotificationChannelAdapter {

    @Override
    public boolean supports(String channel) {
        return "INBOX".equals(channel);
    }

    @Override
    public DeliveryResult send(Notification notification, String channel, NotifyPreference preference) {
        return DeliveryResult.sent();
    }
}
