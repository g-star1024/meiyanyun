package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单级权益核销单测（棒⑤卡3 L161：orderNo 幂等锚重放零副作用、行锁整笔扣次、
 * 次数不足/无钱包 422 中文整笔回滚不落流水、参数与客户已合并中文 400）。
 * UI 手核销 writeoff 链路由三轨真验覆盖（本类聚焦订单级新语义）。
 */
@ExtendWith(MockitoExtension.class)
class BenefitServiceTest {

    @Mock private MemberBenefitWalletRepository walletRepo;
    @Mock private MemberBenefitWriteoffRepository writeoffRepo;
    @Mock private MemberLevelRepository levelRepo;
    @Mock private RefNameResolver nameResolver;
    @Mock private AuditRecorder audit;
    @Mock private PlatformTransactionManager txManager;

    private BenefitService service;

    @BeforeEach
    void setUp() {
        service = new BenefitService(walletRepo, writeoffRepo, levelRepo, nameResolver, audit, txManager);
    }

    private Customer customer() {
        Customer c = new Customer();
        c.setCustomerId("M0001");
        c.setLevel("黑卡");
        c.setStoreCode("SST01");
        return c;
    }

    private MemberBenefitWallet wallet(int total, int used) {
        MemberBenefitWallet w = new MemberBenefitWallet();
        w.setWalletId(9L);
        w.setCustomerId("M0001");
        w.setPeriod(BenefitService.currentPeriod());
        w.setBenefitType(BenefitService.FREE_CARE);
        w.setLevelSnap("黑卡");
        w.setTotalTimes(total);
        w.setUsedTimes(used);
        return w;
    }

    private MemberBenefitWriteoff flow(String writeoffNo, String orderNo, String project) {
        MemberBenefitWriteoff f = new MemberBenefitWriteoff();
        f.setWriteoffNo(writeoffNo);
        f.setCustomerId("M0001");
        f.setPeriod(BenefitService.currentPeriod());
        f.setBenefitType(BenefitService.FREE_CARE);
        f.setLevelSnap("黑卡");
        f.setProjectName(project);
        f.setStatus("OK");
        f.setClientRequestId("ORD-" + orderNo + "-1");
        f.setOperator("system");
        f.setOrderNo(orderNo);
        return f;
    }

    // ==================== 订单级核销（棒⑤卡3 L161） ====================

    @Test
    void writeoffForOrder_ok_deductsAllProjectsAndAnchorsOrderNo() {
        MemberBenefitWallet w = wallet(2, 0);
        when(walletRepo.findByCustomerIdAndPeriodAndBenefitType("M0001", BenefitService.currentPeriod(),
                BenefitService.FREE_CARE)).thenReturn(Optional.of(w));
        when(walletRepo.findForUpdate(9L)).thenReturn(Optional.of(w));
        when(writeoffRepo.maxSeqOfDay(anyString())).thenReturn(0L, 1L);
        when(writeoffRepo.saveAndFlush(any(MemberBenefitWriteoff.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<BenefitService.WriteoffResult> results = service.writeoffForOrder(
                customer(), "OD20261002-000001", List.of("补水护理", "舒缓护理"));

        assertEquals(2, results.size());
        assertTrue(results.get(0).ok() && results.get(1).ok());
        assertEquals("OK", results.get(0).status());
        assertTrue(results.get(0).writeoffNo().startsWith("BW"));
        assertNotEquals(results.get(0).writeoffNo(), results.get(1).writeoffNo());
        assertEquals(1, results.get(0).remaining());
        assertEquals(0, results.get(1).remaining());
        assertEquals(2, w.getUsedTimes());

        ArgumentCaptor<MemberBenefitWriteoff> captor = ArgumentCaptor.forClass(MemberBenefitWriteoff.class);
        verify(writeoffRepo, times(2)).saveAndFlush(captor.capture());
        List<MemberBenefitWriteoff> saved = captor.getAllValues();
        assertEquals("OD20261002-000001", saved.get(0).getOrderNo());
        assertEquals("OD20261002-000001", saved.get(1).getOrderNo());
        assertEquals("ORD-OD20261002-000001-1", saved.get(0).getClientRequestId());
        assertEquals("ORD-OD20261002-000001-2", saved.get(1).getClientRequestId());
        assertEquals("补水护理", saved.get(0).getProjectName());
        assertEquals("舒缓护理", saved.get(1).getProjectName());
        assertEquals("OK", saved.get(0).getStatus());
        verify(audit, times(2)).record(eq("BENEFIT"), anyString(), eq("system"), eq("WRITEOFF"),
                contains("OD20261002-000001"));
        verify(walletRepo).save(w);
    }

    @Test
    void writeoffForOrder_replayByOrderNo_returnsExistingWithoutSideEffects() {
        when(writeoffRepo.findByOrderNoOrderByWriteoffIdAsc("OD20261002-000001"))
                .thenReturn(List.of(flow("BW20261002-000003", "OD20261002-000001", "补水护理"),
                        flow("BW20261002-000004", "OD20261002-000001", "舒缓护理")));
        when(walletRepo.findByCustomerIdAndPeriodAndBenefitType(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(wallet(2, 2)));

        List<BenefitService.WriteoffResult> results = service.writeoffForOrder(
                customer(), "OD20261002-000001", List.of("补水护理", "舒缓护理"));

        assertEquals(2, results.size());
        assertEquals("BW20261002-000003", results.get(0).writeoffNo());
        assertEquals("BW20261002-000004", results.get(1).writeoffNo());
        assertTrue(results.stream().allMatch(BenefitService.WriteoffResult::ok));
        assertEquals(0, results.get(0).remaining());
        verify(walletRepo, never()).findForUpdate(any());
        verify(writeoffRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void writeoffForOrder_firstProjectExhausted_throws422WithoutSideEffects() {
        MemberBenefitWallet w = wallet(1, 1);
        when(walletRepo.findByCustomerIdAndPeriodAndBenefitType("M0001", BenefitService.currentPeriod(),
                BenefitService.FREE_CARE)).thenReturn(Optional.of(w));
        when(walletRepo.findForUpdate(9L)).thenReturn(Optional.of(w));

        CustomerService.Unprocessable ex = assertThrows(CustomerService.Unprocessable.class,
                () -> service.writeoffForOrder(customer(), "OD20261002-000002", List.of("补水护理")));
        assertTrue(ex.getMessage().contains("免费护理次数不足"));
        assertTrue(ex.getMessage().contains("补水护理"));
        verify(writeoffRepo, never()).saveAndFlush(any());
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void writeoffForOrder_secondProjectExhausted_throws422() {
        MemberBenefitWallet w = wallet(1, 0);
        when(walletRepo.findByCustomerIdAndPeriodAndBenefitType("M0001", BenefitService.currentPeriod(),
                BenefitService.FREE_CARE)).thenReturn(Optional.of(w));
        when(walletRepo.findForUpdate(9L)).thenReturn(Optional.of(w));
        when(writeoffRepo.maxSeqOfDay(anyString())).thenReturn(0L);
        when(writeoffRepo.saveAndFlush(any(MemberBenefitWriteoff.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CustomerService.Unprocessable ex = assertThrows(CustomerService.Unprocessable.class,
                () -> service.writeoffForOrder(customer(), "OD20261002-000003",
                        List.of("补水护理", "舒缓护理")));
        assertTrue(ex.getMessage().contains("免费护理次数不足"));
        assertTrue(ex.getMessage().contains("舒缓护理"));
        // 首项目已落流水但 422 抛出 → 运行时 @Transactional 整笔回滚（三轨真验验证库内零残留）
        verify(writeoffRepo, times(1)).saveAndFlush(any());
        verify(audit, times(1)).record(any(), any(), any(), any(), any());
    }

    @Test
    void writeoffForOrder_noWallet_throws422() {
        Customer c = customer();
        c.setLevel("普通");

        CustomerService.Unprocessable ex = assertThrows(CustomerService.Unprocessable.class,
                () -> service.writeoffForOrder(c, "OD20261002-000004", List.of("补水护理")));
        assertTrue(ex.getMessage().contains("当前等级无免费护理权益"));
        verify(walletRepo, never()).findForUpdate(any());
        verify(writeoffRepo, never()).saveAndFlush(any());
    }

    @Test
    void writeoffForOrder_blankAndInvalidParams_throws400() {
        Customer c = customer();
        assertThrows(CustomerService.BadReq.class,
                () -> service.writeoffForOrder(c, "  ", List.of("补水护理")));
        assertThrows(CustomerService.BadReq.class,
                () -> service.writeoffForOrder(c, "OD20261002-000005", null));
        assertThrows(CustomerService.BadReq.class,
                () -> service.writeoffForOrder(c, "OD20261002-000005", List.of()));
        assertThrows(CustomerService.BadReq.class,
                () -> service.writeoffForOrder(c, "OD20261002-000005", List.of("  ")));
        assertThrows(CustomerService.BadReq.class,
                () -> service.writeoffForOrder(c, "OD20261002-000005", List.of("项".repeat(41))));
        assertThrows(CustomerService.BadReq.class,
                () -> service.writeoffForOrder(c, "O".repeat(25), List.of("补水护理")));
        verify(writeoffRepo, never()).findByOrderNoOrderByWriteoffIdAsc(anyString());
    }

    @Test
    void writeoffForOrder_mergedCustomer_throws400() {
        Customer c = customer();
        c.setMergedInto("M0002");

        CustomerService.BadReq ex = assertThrows(CustomerService.BadReq.class,
                () -> service.writeoffForOrder(c, "OD20261002-000006", List.of("补水护理")));
        assertTrue(ex.getMessage().contains("已合并"));
        verify(writeoffRepo, never()).saveAndFlush(any());
    }
}
