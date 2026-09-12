package com.meiyun.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.txn.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款终审「赠金→卡本金→法币」级联单测（JUnit5 + Mockito，B39）：
 * 三段混合单各段联动与资金 outbox 分录、纯赠金单不产生资金事件、纯法币单不触发赠金联动、
 * 营销域失败时整笔不终审（不置 REFUNDED、不调卡域、不入 outbox）。
 * FinanceEventPublisher 用真实实现（仅 mock 两仓储），保证拆分口径与生产一致。
 */
@ExtendWith(MockitoExtension.class)
class TxnServiceTest {

    @Mock TxnRefundRepository refundRepo;
    @Mock TxnCardCancelRepository cancelRepo;
    @Mock TxnOrderRepository orderRepo;
    @Mock MemberCardRepository cardRepo;
    @Mock AuditRecorder audit;
    @Mock ApprovalService approvalService;
    @Mock OrderPaymentRepository payRepo;
    @Mock CustomerCardClient cardClient;
    @Mock MarketingGrantClient grantClient;

    FinanceEventPublisher financeEvents;
    TxnService service;

    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        financeEvents = publisherCapturing(new ArrayList<>());
        service = new TxnService(refundRepo, cancelRepo, orderRepo, cardRepo, audit,
                approvalService, financeEvents, cardClient, grantClient);
        lenient().when(refundRepo.save(any(TxnRefund.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** 真实 FinanceEventPublisher + mock outbox（save 落到传入列表），供断言资金分录。 */
    private FinanceEventPublisher publisherCapturing(List<FinanceEvent> sink) {
        FinanceEventRepository eventRepo = mock(FinanceEventRepository.class);
        lenient().when(eventRepo.save(any(FinanceEvent.class))).thenAnswer(i -> {
            sink.add(i.getArgument(0));
            return i.getArgument(0);
        });
        return new FinanceEventPublisher(eventRepo, payRepo);
    }

    private OrderPayment payment(String method, long posted) {
        OrderPayment p = new OrderPayment();
        p.setPayMethod(method);
        p.setPostedAmount(posted);
        return p;
    }

    private TxnRefund refund(String no, long refundAmt, String channel) {
        TxnRefund r = new TxnRefund();
        r.setTxnNo(no);
        r.setOrderNo("OD20260912-000001");
        r.setStoreCode("ST-SH-001");
        r.setCustomer("M0001");
        r.setCustomerName("王梅");
        r.setChannel(channel);
        r.setRefundAmt(refundAmt);
        r.setStatus("PENDING_FINANCE");
        return r;
    }

    private void stubRefund(TxnRefund r, List<OrderPayment> pays) {
        when(refundRepo.findById(r.getTxnNo())).thenReturn(Optional.of(r));
        when(payRepo.findByOrderNoOrderByPaymentIdAsc(r.getOrderNo())).thenReturn(pays);
    }

    private JsonNode payload(List<FinanceEvent> events, int index) {
        try {
            return MAPPER.readTree(events.get(index).getPayload());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void confirmRefund_mixedThreeWaySplitCascadesGrantThenBalance() {
        TxnRefund r = refund("RF20260912-000001", 10000L, "ORIGINAL");
        stubRefund(r, List.of(payment("grant", 3000L), payment("balance", 2000L), payment("cash", 5000L)));

        service.confirmRefund(r.getTxnNo(), new TxnService.ApprovalCmd("E001", null));

        assertEquals("REFUNDED", r.getStatus());
        assertEquals("system", r.getFinanceBy());
        assertNotNull(r.getRefundedAt());

        InOrder order = inOrder(grantClient, cardClient);
        order.verify(grantClient).refund("M0001", 3000L, r.getOrderNo(), r.getTxnNo(), "ST-SH-001", "system");
        order.verify(cardClient).refundForOrder(r.getTxnNo(), r.getOrderNo(), 2000L);
    }

    @Test
    void confirmRefund_mixedSplitOutboxHasBalanceAndCashEntriesWithoutGrant() {
        TxnRefund r = refund("RF20260912-000002", 10000L, "ORIGINAL");
        List<FinanceEvent> events = new ArrayList<>();
        financeEvents = publisherCapturing(events);
        service = new TxnService(refundRepo, cancelRepo, orderRepo, cardRepo, audit,
                approvalService, financeEvents, cardClient, grantClient);
        stubRefund(r, List.of(payment("grant", 3000L), payment("balance", 2000L), payment("cash", 5000L)));

        service.confirmRefund(r.getTxnNo(), new TxnService.ApprovalCmd("E001", null));

        assertEquals(1, events.size());
        assertEquals("REFUND_CONFIRMED", events.get(0).getEventType());
        JsonNode entries = payload(events, 0);
        assertEquals(2, entries.size());
        JsonNode balanceEntry = entries.get(0);
        assertEquals("RF-DEPOSIT", balanceEntry.get("subject").asText());
        assertEquals("IN", balanceEntry.get("direction").asText());
        assertEquals(2000L, balanceEntry.get("amount").asLong());
        assertEquals("balance", balanceEntry.get("channel").asText());
        JsonNode cashEntry = entries.get(1);
        assertEquals("RF-REFUND", cashEntry.get("subject").asText());
        assertEquals("OUT", cashEntry.get("direction").asText());
        assertEquals(5000L, cashEntry.get("amount").asLong());
        assertEquals("cash", cashEntry.get("channel").asText());
    }

    @Test
    void confirmRefund_pureGrantRefundEmitsNoFinanceEvent() {
        TxnRefund r = refund("RF20260912-000003", 3000L, "ORIGINAL");
        List<FinanceEvent> events = new ArrayList<>();
        financeEvents = publisherCapturing(events);
        service = new TxnService(refundRepo, cancelRepo, orderRepo, cardRepo, audit,
                approvalService, financeEvents, cardClient, grantClient);
        stubRefund(r, List.of(payment("grant", 3000L)));

        service.confirmRefund(r.getTxnNo(), new TxnService.ApprovalCmd("E001", null));

        assertEquals("REFUNDED", r.getStatus());
        verify(grantClient).refund(eq("M0001"), eq(3000L), eq(r.getOrderNo()), eq(r.getTxnNo()), anyString(), anyString());
        verify(cardClient, never()).refundForOrder(anyString(), anyString(), anyLong());
        assertTrue(events.isEmpty());
    }

    @Test
    void confirmRefund_pureCashDoesNotCallGrantOrBalance() {
        TxnRefund r = refund("RF20260912-000004", 5000L, "CASH");
        List<FinanceEvent> events = new ArrayList<>();
        financeEvents = publisherCapturing(events);
        service = new TxnService(refundRepo, cancelRepo, orderRepo, cardRepo, audit,
                approvalService, financeEvents, cardClient, grantClient);
        stubRefund(r, List.of(payment("cash", 5000L)));

        service.confirmRefund(r.getTxnNo(), new TxnService.ApprovalCmd("E001", null));

        verify(grantClient, never()).refund(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(cardClient, never()).refundForOrder(anyString(), anyString(), anyLong());
        JsonNode entries = payload(events, 0);
        assertEquals(1, entries.size());
        assertEquals(5000L, entries.get(0).get("amount").asLong());
        assertEquals("cash", entries.get(0).get("channel").asText());
    }

    @Test
    void confirmRefund_grantRemoteFailureAbortsWholeConfirm() {
        TxnRefund r = refund("RF20260912-000005", 10000L, "ORIGINAL");
        List<FinanceEvent> events = new ArrayList<>();
        financeEvents = publisherCapturing(events);
        service = new TxnService(refundRepo, cancelRepo, orderRepo, cardRepo, audit,
                approvalService, financeEvents, cardClient, grantClient);
        stubRefund(r, List.of(payment("grant", 3000L), payment("cash", 7000L)));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "赠金退款回加失败：营销服务暂不可用，请稍后重试（本笔操作已回滚，未扣款未记账）"))
                .when(grantClient).refund(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.confirmRefund(r.getTxnNo(), new TxnService.ApprovalCmd("E001", null)));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());

        assertEquals("PENDING_FINANCE", r.getStatus());
        assertNull(r.getRefundedAt());
        verify(cardClient, never()).refundForOrder(anyString(), anyString(), anyLong());
        verify(refundRepo, never()).save(any());
        assertTrue(events.isEmpty());
    }
}
