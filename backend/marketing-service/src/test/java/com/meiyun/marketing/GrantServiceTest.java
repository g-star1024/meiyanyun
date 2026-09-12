package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款终审赠金回加单测（JUnit5 + Mockito，B39）：
 * 全额回加 USED 券复活 VALID、逆向 FIFO 跨券部分回加、过期券保态加额、
 * RF 单号幂等重放、按原单累计封顶防超回、原单无 DEDUCT 422、参数校验 400。
 */
@ExtendWith(MockitoExtension.class)
class GrantServiceTest {

    @Mock GrantRuleRepository ruleRepo;
    @Mock CustomerGrantRepository grantRepo;
    @Mock GrantDeductionRepository deductionRepo;
    @Mock AuditRecorder audit;
    @Mock BizNoGenerator bizNoGenerator;
    @Mock CustomerDirectoryClient customerDirectory;

    GrantService service;

    static final String CID = "M0001";
    static final String OD = "OD20260912-000001";
    static final String RF = "RF20260912-000001";

    @BeforeEach
    void setUp() {
        service = new GrantService(ruleRepo, grantRepo, deductionRepo, audit,
                bizNoGenerator, customerDirectory);
        lenient().when(grantRepo.save(any(CustomerGrant.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(deductionRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        lenient().when(grantRepo.sumBalanceByCustomer(anyString())).thenReturn(0L);
    }

    private CustomerGrant grant(long id, long balance, String status, boolean expired) {
        CustomerGrant g = new CustomerGrant();
        g.setId(id);
        g.setCustomerId(CID);
        g.setAmountFen(5000L);
        g.setBalanceFen(balance);
        g.setStatus(status);
        g.setExpireAt(expired ? OffsetDateTime.now().minusDays(1) : OffsetDateTime.now().plusDays(30));
        return g;
    }

    private GrantDeduction deduct(long grantId, long amount) {
        GrantDeduction d = new GrantDeduction();
        d.setBizRef(OD);
        d.setGrantId(grantId);
        d.setCustomerId(CID);
        d.setAmountFen(amount);
        d.setBalanceAfterFen(0L);
        d.setChangeType("DEDUCT");
        return d;
    }

    private void stubRefundContext(List<GrantDeduction> deducts, List<CustomerGrant> grants,
                                   List<Object[]> refundedRows) {
        when(deductionRepo.findByBizRefOrderByIdAsc(anyString())).thenAnswer(inv ->
                OD.equals(inv.getArgument(0)) ? deducts : List.of());
        when(deductionRepo.sumRefundedByOriginGroupByGrant(OD)).thenReturn(refundedRows);
        when(grantRepo.findByIdInForUpdate(anyList())).thenReturn(grants);
    }

    @Test
    void refund_fullAmountRestoresUsedGrantToValid() {
        CustomerGrant g = grant(1L, 0L, "USED", false);
        stubRefundContext(List.of(deduct(1L, 5000L)), List.of(g), List.of());

        List<GrantDeduction> rows = service.refund(CID, 5000L, OD, RF, "ST-SH-001", "E001");

        assertEquals(1, rows.size());
        assertEquals(5000L, g.getBalanceFen());
        assertEquals("VALID", g.getStatus());
        GrantDeduction row = rows.get(0);
        assertEquals("REFUND", row.getChangeType());
        assertEquals(RF, row.getBizRef());
        assertEquals(OD, row.getOriginBizRef());
        assertEquals(5000L, row.getAmountFen());
        assertEquals(5000L, row.getBalanceAfterFen());
        verify(audit).record(eq("GRANT_REFUND"), eq(RF), anyString(), eq("ORDER"), anyString());
    }

    @Test
    void refund_partialUsesReverseFifoAcrossGrants() {
        // 原抵扣顺序：券1 先扣 5000（先到期），券2 后扣 5000；回加 7000 应先回券2 全额再回券1 2000
        CustomerGrant g1 = grant(1L, 0L, "USED", false);
        CustomerGrant g2 = grant(2L, 0L, "USED", false);
        stubRefundContext(List.of(deduct(1L, 5000L), deduct(2L, 5000L)),
                List.of(g1, g2), List.of());

        List<GrantDeduction> rows = service.refund(CID, 7000L, OD, RF, "ST-SH-001", "E001");

        assertEquals(2, rows.size());
        assertEquals(2L, rows.get(0).getGrantId());
        assertEquals(5000L, rows.get(0).getAmountFen());
        assertEquals(1L, rows.get(1).getGrantId());
        assertEquals(2000L, rows.get(1).getAmountFen());
        assertEquals(5000L, g2.getBalanceFen());
        assertEquals("VALID", g2.getStatus());
        assertEquals(2000L, g1.getBalanceFen());
        assertEquals("VALID", g1.getStatus());
    }

    @Test
    void refund_expiredGrantKeepsStatusButBalanceAdded() {
        CustomerGrant g = grant(1L, 0L, "EXPIRED", true);
        stubRefundContext(List.of(deduct(1L, 5000L)), List.of(g), List.of());

        List<GrantDeduction> rows = service.refund(CID, 5000L, OD, RF, "ST-SH-001", "E001");

        assertEquals(1, rows.size());
        assertEquals(5000L, g.getBalanceFen());
        assertEquals("EXPIRED", g.getStatus());
    }

    @Test
    void refund_replayByRefundNoReturnsExistingRowsAndTouchesNothing() {
        GrantDeduction existing = new GrantDeduction();
        existing.setBizRef(RF);
        existing.setOriginBizRef(OD);
        existing.setGrantId(1L);
        existing.setChangeType("REFUND");
        existing.setAmountFen(5000L);
        when(deductionRepo.findByBizRefOrderByIdAsc(RF)).thenReturn(List.of(existing));

        List<GrantDeduction> rows = service.refund(CID, 5000L, OD, RF, "ST-SH-001", "E001");

        assertEquals(1, rows.size());
        assertSame(existing, rows.get(0));
        verify(grantRepo, never()).save(any());
        verify(deductionRepo, never()).saveAll(anyList());
        verify(deductionRepo, never()).findByBizRefOrderByIdAsc(OD);
    }

    @Test
    void refund_cumulativeCapBlocksOverRefund422() {
        // 原扣 5000，历史已回 3000（同 origin 另一张 RF），本次再回 3000 → 可回仅 2000 → 422（锁券前即拒绝）
        Object[] refundedRow = new Object[]{1L, 3000L};
        when(deductionRepo.findByBizRefOrderByIdAsc(anyString())).thenAnswer(inv ->
                OD.equals(inv.getArgument(0)) ? List.of(deduct(1L, 5000L)) : List.of());
        when(deductionRepo.sumRefundedByOriginGroupByGrant(OD))
                .thenReturn(Collections.singletonList(refundedRow));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refund(CID, 3000L, OD, "RF20260912-000002", "ST-SH-001", "E001"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("可回赠金不足"));
        verify(grantRepo, never()).save(any());
        verify(deductionRepo, never()).saveAll(anyList());
    }

    @Test
    void refund_cumulativeWithinCapSucceeds() {
        CustomerGrant g = grant(1L, 3000L, "VALID", false);
        Object[] refundedRow = new Object[]{1L, 3000L};
        stubRefundContext(List.of(deduct(1L, 5000L)), List.of(g),
                Collections.singletonList(refundedRow));

        List<GrantDeduction> rows = service.refund(CID, 2000L, OD, "RF20260912-000002",
                "ST-SH-001", "E001");

        assertEquals(1, rows.size());
        assertEquals(2000L, rows.get(0).getAmountFen());
        assertEquals(5000L, g.getBalanceFen());
    }

    @Test
    void refund_orderWithoutDeductionRejected422() {
        when(deductionRepo.findByBizRefOrderByIdAsc(RF)).thenReturn(List.of());
        when(deductionRepo.findByBizRefOrderByIdAsc(OD)).thenReturn(new ArrayList<>());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refund(CID, 1000L, OD, RF, "ST-SH-001", "E001"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("无赠金抵扣记录"));
        verify(deductionRepo, never()).saveAll(anyList());
    }

    @Test
    void refund_invalidArgsRejected400() {
        assertThrows(ResponseStatusException.class,
                () -> service.refund(" ", 1000L, OD, RF, "S", "E"));
        assertThrows(ResponseStatusException.class,
                () -> service.refund(CID, 0L, OD, RF, "S", "E"));
        assertThrows(ResponseStatusException.class,
                () -> service.refund(CID, 1000L, " ", RF, "S", "E"));
        assertThrows(ResponseStatusException.class,
                () -> service.refund(CID, 1000L, OD, " ", "S", "E"));
    }
}
