package com.meiyun.marketing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费满额自动发赠金引擎单测（JUnit5 + Mockito）：
 * 覆盖门槛/优先级/门店匹配、售卡单排除、客户与脏数据跳过、订单级跨规则去重、
 * 幂等重放不计数、客户域 502 中止不推进游标、空规则与空订单窗口。
 */
@ExtendWith(MockitoExtension.class)
class AutoGrantServiceTest {

    @Mock
    TxnInternalClient txnClient;
    @Mock
    GrantRuleRepository ruleRepo;
    @Mock
    CustomerGrantRepository grantRepo;
    @Mock
    GrantService grantService;
    @Mock
    AutoGrantStateRepository stateRepo;

    AutoGrantService service;

    AutoGrantState state;

    @BeforeEach
    void setUp() {
        service = new AutoGrantService(txnClient, ruleRepo, grantRepo, grantService, stateRepo);
        state = new AutoGrantState();
        state.setStateId(1);
        lenient().when(stateRepo.findById(1)).thenReturn(Optional.of(state));
        lenient().when(stateRepo.save(any(AutoGrantState.class))).thenAnswer(i -> i.getArgument(0));
    }

    private GrantRule rule(String id, long threshold, long amount, int priority, String stores) {
        GrantRule r = new GrantRule();
        r.setRuleId(id);
        r.setName("满赠规则-" + id);
        r.setGrantType("CONSUME_THRESHOLD");
        r.setThresholdFen(threshold);
        r.setGrantAmountFen(amount);
        r.setExpireMonths(3);
        r.setApplicableStores(stores);
        r.setStatus("ENABLED");
        r.setPriority(priority);
        return r;
    }

    private TxnInternalClient.PaidOrder order(String no, String customer, String store,
                                              long amount, String bizKind) {
        return new TxnInternalClient.PaidOrder(no, customer, store, amount, "已收款",
                bizKind, OffsetDateTime.now());
    }

    /** 桩规则：仅一条满 100 元赠 20 元、全部门店规则。 */
    private void stubSingleRule() {
        when(ruleRepo.findByStatusOrderByPriorityAsc("ENABLED"))
                .thenReturn(List.of(rule("GRT1", 10_000L, 2_000L, 0, "")));
    }

    // ==================== 命中与发放 ====================

    @Test
    void order_reaching_threshold_triggers_issue_by_rule_and_advances_cursor() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(order("OD001", "M0001", "ST-BJ-001", 12_000L, "SERVICE")));
        when(grantRepo.existsBySourceBizRef("OD001")).thenReturn(false);

        AutoGrantService.ScanResult res = service.scan();

        assertNull(res.error());
        assertEquals(1, res.scanned());
        assertEquals(1, res.granted());
        verify(grantService).issueByRule(eq("M0001"), any(GrantRule.class), eq("OD001"));
        assertNotNull(state.getLastRunAt());
        assertEquals(1L, state.getLastScanned());
        assertEquals(1L, state.getLastGranted());
        verify(stateRepo).save(state);
    }

    @Test
    void order_below_threshold_is_scanned_but_not_granted() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(order("OD002", "M0001", "ST-BJ-001", 9_999L, "SERVICE")));

        AutoGrantService.ScanResult res = service.scan();

        assertEquals(1, res.scanned());
        assertEquals(0, res.granted());
        verify(grantService, never()).issueByRule(anyString(), any(), anyString());
        verify(stateRepo).save(state);
    }

    @Test
    void first_matching_rule_by_priority_wins_and_single_order_gets_one_grant() {
        GrantRule low = rule("GRT-LOW", 5_000L, 1_000L, 10, "");
        GrantRule high = rule("GRT-HIGH", 5_000L, 5_000L, 1, "");
        when(ruleRepo.findByStatusOrderByPriorityAsc("ENABLED")).thenReturn(List.of(high, low));
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(order("OD003", "M0002", "ST-BJ-001", 8_000L, "SERVICE")));
        when(grantRepo.existsBySourceBizRef("OD003")).thenReturn(false);

        service.scan();

        verify(grantService).issueByRule(eq("M0002"), eq(high), eq("OD003"));
        verify(grantService, never()).issueByRule(eq("M0002"), eq(low), eq("OD003"));
    }

    // ==================== 排除与去重 ====================

    @Test
    void card_sale_orders_are_excluded_from_consume_grant() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(order("OD004", "M0003", "ST-BJ-001", 50_000L, "CARD_SALE")));

        AutoGrantService.ScanResult res = service.scan();

        assertEquals(1, res.scanned());
        assertEquals(0, res.granted());
        verify(grantService, never()).issueByRule(anyString(), any(), anyString());
    }

    @Test
    void orders_without_customer_or_non_positive_amount_are_skipped() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString())).thenReturn(List.of(
                order("OD005", "  ", "ST-BJ-001", 12_000L, "SERVICE"),
                order("OD006", "M0004", "ST-BJ-001", 0L, "SERVICE")));

        AutoGrantService.ScanResult res = service.scan();

        assertEquals(2, res.scanned());
        assertEquals(0, res.granted());
        verify(grantService, never()).issueByRule(anyString(), any(), anyString());
    }

    @Test
    void already_granted_order_by_any_rule_is_skipped_via_source_ref() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(order("OD007", "M0005", "ST-BJ-001", 20_000L, "SERVICE")));
        // 该订单此前已被（可能不同的）规则发放过
        when(grantRepo.existsBySourceBizRef("OD007")).thenReturn(true);

        AutoGrantService.ScanResult res = service.scan();

        assertEquals(0, res.granted());
        verify(grantService, never()).issueByRule(anyString(), any(), anyString());
    }

    // ==================== 门店匹配 ====================

    @Test
    void store_scoped_rule_matches_only_listed_store() {
        when(ruleRepo.findByStatusOrderByPriorityAsc("ENABLED"))
                .thenReturn(List.of(rule("GRT2", 1_000L, 2_000L, 0, "ST-SH-001,ST-GZ-001")));
        when(txnClient.fetchPaidOrders(anyString(), anyString())).thenReturn(List.of(
                order("OD008", "M0006", "ST-SH-001", 5_000L, "SERVICE"),
                order("OD009", "M0007", "ST-BJ-001", 5_000L, "SERVICE")));
        when(grantRepo.existsBySourceBizRef("OD008")).thenReturn(false);

        AutoGrantService.ScanResult res = service.scan();

        assertEquals(2, res.scanned());
        assertEquals(1, res.granted());
        verify(grantService).issueByRule(eq("M0006"), any(GrantRule.class), eq("OD008"));
        verify(grantService, never()).issueByRule(eq("M0007"), any(), eq("OD009"));
    }

    // ==================== 故障分层 ====================

    @Test
    void txn_unavailable_aborts_scan_and_keeps_cursor() {
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenThrow(new TxnServiceUnavailableException("连接被拒", new RuntimeException()));

        AutoGrantService.ScanResult res = service.scan();

        assertEquals(0, res.scanned());
        assertNotNull(res.error());
        verify(grantService, never()).issueByRule(anyString(), any(), anyString());
        verify(stateRepo, never()).save(any());
    }

    @Test
    void missing_customer_400_skips_order_but_cursor_advances() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(
                        order("OD010", "M-GONE", "ST-BJ-001", 12_000L, "SERVICE"),
                        order("OD011", "M0008", "ST-BJ-001", 12_000L, "SERVICE")));
        when(grantRepo.existsBySourceBizRef("OD010")).thenReturn(false);
        when(grantRepo.existsBySourceBizRef("OD011")).thenReturn(false);
        when(grantService.issueByRule(eq("M-GONE"), any(), eq("OD010")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户不存在，不可发赠金：M-GONE"));

        AutoGrantService.ScanResult res = service.scan();

        assertNull(res.error());
        assertEquals(2, res.scanned());
        assertEquals(1, res.granted());
        verify(grantService).issueByRule(eq("M0008"), any(), eq("OD011"));
        verify(stateRepo).save(state);
    }

    @Test
    void customer_domain_502_aborts_whole_round_without_cursor_advance() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(order("OD012", "M0009", "ST-BJ-001", 12_000L, "SERVICE")));
        when(grantRepo.existsBySourceBizRef("OD012")).thenReturn(false);
        when(grantService.issueByRule(eq("M0009"), any(), eq("OD012")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "客户域暂不可用，发赠金中止"));

        AutoGrantService.ScanResult res = service.scan();

        assertNotNull(res.error());
        assertEquals(0, res.granted());
        verify(stateRepo, never()).save(any());
    }

    // ==================== 空转 ====================

    @Test
    void no_enabled_rule_scans_but_never_grants_and_advances_cursor() {
        when(ruleRepo.findByStatusOrderByPriorityAsc("ENABLED")).thenReturn(List.of());
        when(txnClient.fetchPaidOrders(anyString(), anyString()))
                .thenReturn(List.of(order("OD013", "M0010", "ST-BJ-001", 99_000L, "SERVICE")));

        AutoGrantService.ScanResult res = service.scan();

        assertNull(res.error());
        assertEquals(1, res.scanned());
        assertEquals(0, res.granted());
        verify(grantService, never()).issueByRule(anyString(), any(), anyString());
        verify(stateRepo).save(state);
    }

    @Test
    void empty_paid_window_advances_cursor() {
        stubSingleRule();
        when(txnClient.fetchPaidOrders(anyString(), anyString())).thenReturn(List.of());

        AutoGrantService.ScanResult res = service.scan();

        assertNull(res.error());
        assertEquals(0, res.scanned());
        assertEquals(0, res.granted());
        assertNotNull(state.getLastRunAt());
        verify(stateRepo).save(state);
    }
}
