package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 优惠券写链路单测（JUnit5 + Mockito）：
 * 覆盖创建校验、状态机（启用/停用 + 幂等 + 过期拦截）、发券防超发（409/部分发放/计数回写）与全动作审计。
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock
    CouponTemplateRepository couponRepo;
    @Mock
    CouponGrantRepository grantRepo;
    @Mock
    BizNoGenerator noGen;
    @Mock
    AuditRecorder audit;

    CouponService service;

    @BeforeEach
    void setUp() {
        service = new CouponService(couponRepo, grantRepo, noGen, audit);
        lenient().when(noGen.next(eq("CPN"), any())).thenReturn("CPN20260902-000001");
        lenient().when(noGen.next(eq("GR"), any())).thenReturn("GR20260902-000001");
        lenient().when(couponRepo.save(any(CouponTemplate.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(grantRepo.save(any(CouponGrant.class))).thenAnswer(i -> i.getArgument(0));
    }

    private CouponService.CouponCmd validCmd() {
        return new CouponService.CouponCmd(
                "新人满减券", "AMOUNT", 5000L, 10000L, 100,
                "ALL", "全部客户", LocalDate.now(), LocalDate.now().plusDays(30), null);
    }

    private CouponTemplate coupon(String id, String status, int total, int issued) {
        CouponTemplate c = new CouponTemplate();
        c.setCouponId(id);
        c.setCouponName("新人满减券");
        c.setCouponType("AMOUNT");
        c.setFaceValue(5000L);
        c.setThreshold(10000L);
        c.setTotalQty(total);
        c.setIssuedQty(issued);
        c.setUsedQty(0);
        c.setStatus(status);
        c.setGrantScope("ALL");
        c.setValidStart(LocalDate.now().minusDays(1));
        c.setValidEnd(LocalDate.now().plusDays(30));
        c.setCreatedAt(java.time.OffsetDateTime.now());
        return c;
    }

    // ==================== 创建 ====================

    @Test
    void create_happy_path_assigns_no_sets_draft_and_audits() {
        CouponTemplate saved = service.create(validCmd());

        assertEquals("CPN20260902-000001", saved.getCouponId());
        assertEquals("DRAFT", saved.getStatus());
        assertEquals(0, saved.getIssuedQty());
        assertEquals(0, saved.getUsedQty());
        verify(couponRepo).save(any(CouponTemplate.class));
        verify(audit).record(eq("COUPON"), eq("CPN20260902-000001"), anyString(), eq("CREATE"), contains("新人满减券"));
    }

    @Test
    void create_blank_name_rejected_400() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "  ", "AMOUNT", 5000L, 0L, 100, "ALL", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("券名称不可为空"));
        verify(couponRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void create_illegal_type_rejected_400() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券", "BOGUS", 5000L, 0L, 100, "ALL", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("券类型不合法"));
    }

    @Test
    void create_zero_stock_rejected_400() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券", "AMOUNT", 5000L, 0L, 0, "ALL", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("发放库存必须大于 0"));
    }

    @Test
    void create_amount_coupon_non_positive_value_rejected() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券", "AMOUNT", 0L, 0L, 100, "ALL", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("满减金额必须大于 0"));
    }

    @Test
    void create_rate_coupon_value_out_of_range_rejected() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券", "RATE", 1000L, 0L, 100, "ALL", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("折扣率不合法"));
    }

    @Test
    void create_package_without_items_rejected() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券包", "PACKAGE", 5000L, 0L, 100, "ALL", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("券包必须包含子项"));
    }

    @Test
    void create_package_with_broken_json_rejected() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券包", "PACKAGE", 5000L, 0L, 100, "ALL", null, null, null, "[{broken");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("不是合法的 JSON"));
    }

    @Test
    void create_negative_threshold_rejected() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券", "AMOUNT", 5000L, -1L, 100, "ALL", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("使用门槛不可为负"));
    }

    @Test
    void create_illegal_scope_rejected() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券", "AMOUNT", 5000L, 0L, 100, "EVERYONE", null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("发放范围不合法"));
    }

    @Test
    void create_end_before_start_rejected() {
        CouponService.CouponCmd cmd = new CouponService.CouponCmd(
                "券", "AMOUNT", 5000L, 0L, 100, "ALL", null,
                LocalDate.now().plusDays(10), LocalDate.now(), null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("有效期结束日期不可早于开始日期"));
    }

    // ==================== 启用 / 停用 ====================

    @Test
    void enable_draft_to_active_and_audits() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "DRAFT", 100, 0)));
        boolean changed = service.enable("C1");

        assertTrue(changed);
        ArgumentCaptor<CouponTemplate> cap = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(couponRepo).save(cap.capture());
        assertEquals("ACTIVE", cap.getValue().getStatus());
        verify(audit).record(eq("COUPON"), eq("C1"), anyString(), eq("ENABLE"), contains("ACTIVE"));
    }

    @Test
    void enable_already_active_is_idempotent_false_and_no_audit() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 0)));
        boolean changed = service.enable("C1");

        assertFalse(changed);
        verify(couponRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void enable_expired_coupon_rejected_400() {
        CouponTemplate c = coupon("C1", "DRAFT", 100, 0);
        c.setValidEnd(LocalDate.now().minusDays(1));
        when(couponRepo.findById("C1")).thenReturn(Optional.of(c));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.enable("C1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("券已过有效期"));
        verify(couponRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void enable_not_found_404() {
        when(couponRepo.findById("X")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.enable("X"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getReason().contains("券不存在"));
    }

    @Test
    void disable_active_to_disabled_and_audits() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 5)));
        boolean changed = service.disable("C1");

        assertTrue(changed);
        verify(audit).record(eq("COUPON"), eq("C1"), anyString(), eq("DISABLE"), contains("DISABLED"));
    }

    @Test
    void disable_already_disabled_is_idempotent_false_and_no_audit() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "DISABLED", 100, 0)));
        boolean changed = service.disable("C1");

        assertFalse(changed);
        verify(couponRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void disable_draft_coupon_rejected_400() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "DRAFT", 100, 0)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.disable("C1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("不可停用"));
    }

    // ==================== 发券（防超发） ====================

    @Test
    void grant_non_active_coupon_rejected_400() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "DRAFT", 100, 0)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.grant("C1", new CouponService.GrantCmd("ALL", "全部客户", 10)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("仅进行中的券可发放"));
    }

    @Test
    void grant_zero_count_rejected_400() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 0)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.grant("C1", new CouponService.GrantCmd("ALL", "全部客户", 0)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("发放数量必须大于 0"));
    }

    @Test
    void grant_illegal_scope_rejected_400() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 0)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.grant("C1", new CouponService.GrantCmd("BOGUS", "x", 1)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("发放范围不合法"));
    }

    @Test
    void grant_when_stock_exhausted_returns_409_conflict() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 100)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.grant("C1", new CouponService.GrantCmd("ALL", "全部客户", 1)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("库存已发完"));
        verify(couponRepo, never()).save(any(CouponTemplate.class));
        verify(grantRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void grant_partial_when_request_exceeds_left_updates_issued_and_audits_partial() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 95)));
        CouponGrant g = service.grant("C1", new CouponService.GrantCmd("DESIGNATED", "高价值客户", 10));

        assertEquals("GRANTED", g.getStatus());
        assertEquals(5, g.getGrantCount());
        ArgumentCaptor<CouponTemplate> cap = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(couponRepo).save(cap.capture());
        assertEquals(100, cap.getValue().getIssuedQty());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("COUPON"), eq("GR20260902-000001"), anyString(), eq("GRANT"), payload.capture());
        assertTrue(payload.getValue().contains("\"partial\":true"));
        assertTrue(payload.getValue().contains("\"actualCount\":5"));
    }

    @Test
    void grant_full_when_stock_sufficient_sets_count_and_audits() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 0)));
        CouponGrant g = service.grant("C1", new CouponService.GrantCmd("NEW", "本月新客", 20));

        assertEquals(20, g.getGrantCount());
        assertEquals("GR20260902-000001", g.getGrantId());
        ArgumentCaptor<CouponTemplate> cap = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(couponRepo).save(cap.capture());
        assertEquals(20, cap.getValue().getIssuedQty());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("COUPON"), anyString(), anyString(), eq("GRANT"), payload.capture());
        assertTrue(payload.getValue().contains("\"partial\":false"));
    }

    @Test
    void grant_blank_target_name_falls_back_to_dash() {
        when(couponRepo.findById("C1")).thenReturn(Optional.of(coupon("C1", "ACTIVE", 100, 0)));
        CouponGrant g = service.grant("C1", new CouponService.GrantCmd("ALL", "  ", 1));
        assertEquals("—", g.getTargetName());
    }
}
