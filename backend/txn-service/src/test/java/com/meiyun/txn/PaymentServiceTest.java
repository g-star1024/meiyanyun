package com.meiyun.txn;

import com.meiyun.txn.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 收银台「营销赠金抵扣」支付分支单测（JUnit5 + Mockito，B35 资金安全口径）：
 * 散客单禁抵（400）、同单第二笔赠金 409、先扣营销域再落流水的顺序、
 * 营销域 422 余额不足/502 不可用均原样透传且本笔不记账（事务回滚由 @Transactional 保证，此处验证不落库）、
 * 售卡单禁赠金（400）。
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock TxnOrderRepository orderRepo;
    @Mock OrderPaymentRepository payRepo;
    @Mock ConsultPlanService planService;
    @Mock AuditRecorder audit;
    @Mock FinanceEventPublisher financeEvents;
    @Mock CustomerCardClient cardClient;
    @Mock MarketingGrantClient grantClient;

    PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(orderRepo, payRepo, planService, audit,
                financeEvents, cardClient, grantClient);
        lenient().when(orderRepo.save(any(TxnOrder.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(payRepo.save(any(OrderPayment.class))).thenAnswer(i -> i.getArgument(0));
    }

    private TxnOrder order(String no, String status, String bizKind, String customerId, long amount) {
        TxnOrder o = new TxnOrder();
        o.setOrderNo(no);
        o.setStatus(status);
        o.setBizKind(bizKind);
        o.setCustomerId(customerId);
        o.setStoreCode("ST-SH-001");
        o.setAmount(amount);
        return o;
    }

    private OrderPayment payment(String method, long posted) {
        OrderPayment p = new OrderPayment();
        p.setPayMethod(method);
        p.setPostedAmount(posted);
        p.setChangeAmount(0L);
        return p;
    }

    private void stubOrder(TxnOrder o) {
        when(orderRepo.findById(o.getOrderNo())).thenReturn(Optional.of(o));
    }

    private void stubOrderWithPayments(TxnOrder o, List<OrderPayment> history) {
        stubOrder(o);
        when(payRepo.findByOrderNoOrderByPaymentIdAsc(o.getOrderNo())).thenReturn(history);
    }

    @Test
    void grant_walkinOrderWithoutCustomerRejected400() {
        TxnOrder o = order("SO20260912-001", "待收款", "RETAIL", null, 10000L);
        stubOrderWithPayments(o, List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pay(o.getOrderNo(), "grant", 10000L, "E002", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("散客订单"));
        verifyNoInteractions(grantClient);
        verify(payRepo, never()).save(any());
    }

    @Test
    void grant_secondGrantOnSameOrderRejected409() {
        TxnOrder o = order("SO20260912-002", "待收款", "RETAIL", "C0001", 10000L);
        stubOrderWithPayments(o, List.of(payment("grant", 3000L)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pay(o.getOrderNo(), "grant", 3000L, "E002", null));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("已使用营销赠金抵扣过一笔"));
        verifyNoInteractions(grantClient);
        verify(payRepo, never()).save(any());
    }

    @Test
    void grant_cardSaleOrderRejected400() {
        TxnOrder o = order("SO20260912-003", "待收款", "CARD_SALE", "C0001", 50000L);
        stubOrder(o);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pay(o.getOrderNo(), "grant", 50000L, "E002", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("不能使用营销赠金"));
        verifyNoInteractions(grantClient);
        verify(payRepo, never()).save(any());
    }

    @Test
    void grant_successDeductsBeforePersistingPayment() {
        TxnOrder o = order("SO20260912-004", "待收款", "RETAIL", "C0001", 10000L);
        stubOrderWithPayments(o, List.of());
        when(payRepo.maxSeqOfDay(anyString())).thenReturn(0L);

        PaymentService.PayResult r = service.pay(o.getOrderNo(), "grant", 10000L, "E002", null);

        assertTrue(r.completed());
        assertEquals("已收款", o.getStatus());
        verify(grantClient).deduct("C0001", 10000L, "SO20260912-004", "ST-SH-001", "system");
        InOrder inOrder = inOrder(grantClient, payRepo, orderRepo);
        inOrder.verify(grantClient).deduct(anyString(), anyLong(), anyString(), anyString(), anyString());
        inOrder.verify(payRepo).save(any(OrderPayment.class));
        inOrder.verify(orderRepo).save(o);
        verify(financeEvents).emitOrderPaid(o);
    }

    @Test
    void grant_insufficientBalance422PropagatesAndNothingPersisted() {
        TxnOrder o = order("SO20260912-005", "待收款", "RETAIL", "C0001", 10000L);
        stubOrderWithPayments(o, List.of());
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "赠金余额不足，可用 2000 分"))
                .when(grantClient).deduct(anyString(), anyLong(), anyString(), anyString(), anyString());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pay(o.getOrderNo(), "grant", 10000L, "E002", null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("赠金余额不足"));
        verify(payRepo, never()).save(any());
        verify(orderRepo, never()).save(any());
        verifyNoInteractions(audit, financeEvents);
        assertEquals("待收款", o.getStatus());
    }

    @Test
    void grant_marketingDown502PropagatesAndNothingPersisted() {
        TxnOrder o = order("SO20260912-006", "待收款", "RETAIL", "C0001", 10000L);
        stubOrderWithPayments(o, List.of());
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "赠金抵扣失败：营销服务暂不可用，请稍后重试（本笔操作已回滚，未扣款未记账）"))
                .when(grantClient).deduct(anyString(), anyLong(), anyString(), anyString(), anyString());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pay(o.getOrderNo(), "grant", 10000L, "E002", null));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("未扣款未记账"));
        verify(payRepo, never()).save(any());
        verify(orderRepo, never()).save(any());
        verifyNoInteractions(audit, financeEvents);
    }

    @Test
    void grant_mixedPaymentSecondCashLegStillAllowedAfterGrant() {
        TxnOrder o = order("SO20260912-007", "待收款", "RETAIL", "C0001", 10000L);
        stubOrderWithPayments(o, List.of(payment("grant", 4000L)));
        when(payRepo.maxSeqOfDay(anyString())).thenReturn(1L);

        PaymentService.PayResult r = service.pay(o.getOrderNo(), "cash", 6000L, "E002", null);

        assertTrue(r.completed());
        verifyNoInteractions(grantClient);
        verify(planService).markPaidByOrder(eq("SO20260912-007"), anyString());
    }
}
