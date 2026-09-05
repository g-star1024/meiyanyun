package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户域资金事件发布器（B4 充值储值）：会员卡充值成功时，在业务事务内同写
 * {@link CardFinanceEvent}（PENDING），{@link CardFinanceEventRetryJob} 异步投递 finance 落账。
 *
 * <p>客户域只投递充值这一本域发起的资金流入事件（CARD_RECHARGE）：
 * RF-DEPOSIT/IN/CASHIER/RECHARGE，渠道=充值支付方式（cash/card/wxpay/alipay），
 * finance 侧预收池（prepay_pool.total/pending_consume）与账户镜像（account_mirror）自动联动。
 * 余额消费（CONSUME）/退卡（REFUND）发生在 txn 域，其资金分录由 txn 域 outbox 投递，本域不重复投递。
 */
@Service
public class CardFinanceEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CardFinanceEventPublisher.class);

    private final CardFinanceEventRepository eventRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CardFinanceEventPublisher(CardFinanceEventRepository eventRepo) {
        this.eventRepo = eventRepo;
    }

    /** 会员卡充值：单条预收流入分录（渠道=充值支付方式）。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void emitRecharge(String rcNo, String cardNo, long amount, String payMethod,
                             String storeCode, String customerName) {
        String memo = "会员卡充值 · " + nz(customerName, cardNo) + "（" + rcNo + "）";
        List<Map<String, Object>> cmds = List.of(entry(
                "RECHARGE:" + rcNo, rcNo, "RECHARGE",
                "RF-DEPOSIT", "IN", amount, payMethod, "CASHIER", "RECHARGE",
                storeCode, memo));
        enqueue("CARD_RECHARGE", rcNo, cmds);
    }

    // ==================== 内部 ====================

    /** 同事务落 PENDING 事件（负载序列化失败属编程错误，快速失败回滚业务）。 */
    private void enqueue(String eventType, String bizRef, List<Map<String, Object>> cmds) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(cmds);
        } catch (Exception e) {
            log.error("充值资金事件负载序列化失败 eventType={} bizRef={}: {}", eventType, bizRef, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "资金事件内容序列化失败");
        }
        CardFinanceEvent e = new CardFinanceEvent();
        e.setEventType(eventType);
        e.setBizRef(bizRef);
        e.setPayload(payload);
        e.setStatus("PENDING");
        eventRepo.save(e);
        log.info("充值资金事件已入 outbox eventType={} bizRef={} entries={}", eventType, bizRef, cmds.size());
    }

    /** 构建一条与 finance FundEntryCmd 契约逐字段对齐的分录 Map（occurredAt 留空，finance 取受理时刻）。 */
    private Map<String, Object> entry(String idemKey, String bizRef, String bizType, String subject,
                                      String direction, Long amount, String channel, String source,
                                      String refType, String storeCode, String memo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idemKey", idemKey);
        m.put("bizRef", bizRef);
        m.put("bizType", bizType);
        m.put("subject", subject);
        m.put("direction", direction);
        m.put("amount", amount);
        m.put("channel", channel);
        m.put("source", source);
        m.put("refType", refType);
        m.put("storeCode", storeCode);
        m.put("memo", memo);
        m.put("occurredAt", null);
        return m;
    }

    private static String nz(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
