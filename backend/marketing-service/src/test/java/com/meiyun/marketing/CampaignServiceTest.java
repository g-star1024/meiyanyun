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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 营销活动写链路单测（JUnit5 + Mockito）：
 * 覆盖创建（单据号/渠道 JSON/默认值/审计）与状态机流转（合法流转、同态幂等、非法目标、非法流转中文 400）。
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    @Mock
    CampaignRepository campaignRepo;
    @Mock
    BizNoGenerator noGen;
    @Mock
    AuditRecorder audit;

    CampaignService service;

    @BeforeEach
    void setUp() {
        service = new CampaignService(campaignRepo, noGen, audit);
        lenient().when(noGen.next(eq("CP"), any())).thenReturn("CP20260902-000001");
        lenient().when(campaignRepo.save(any(Campaign.class))).thenAnswer(i -> i.getArgument(0));
    }

    private CampaignService.CampaignCmd validCmd() {
        return new CampaignService.CampaignCmd(
                "国庆满减活动", "FULL_REDUCE", List.of("抖音", "小红书"),
                LocalDate.now(), LocalDate.now().plusDays(30),
                5_000_000L, 20_000_000L, null, null, "国庆大促");
    }

    private Campaign campaign(String id, String status) {
        Campaign c = new Campaign();
        c.setCampaignId(id);
        c.setCampaignName("国庆满减活动");
        c.setCampaignType("FULL_REDUCE");
        c.setStatus(status);
        c.setChannels("[\"抖音\"]");
        c.setBudget(5_000_000L);
        c.setSpent(0L);
        c.setTargetAmount(20_000_000L);
        c.setActualAmount(0L);
        c.setNewCustomers(0);
        c.setStoreScope("全部门店");
        c.setOwner("系统");
        c.setCreatedAt(java.time.OffsetDateTime.now());
        return c;
    }

    // ==================== 创建 ====================

    @Test
    void create_happy_path_assigns_no_draft_defaults_and_audits() {
        Campaign saved = service.create(validCmd());

        assertEquals("CP20260902-000001", saved.getCampaignId());
        assertEquals("DRAFT", saved.getStatus());
        assertEquals(0L, saved.getSpent());
        assertEquals(0L, saved.getActualAmount());
        assertEquals(0, saved.getNewCustomers());
        assertEquals("全部门店", saved.getStoreScope());
        assertEquals("系统", saved.getOwner());
        assertTrue(saved.getChannels().contains("抖音"));
        verify(audit).record(eq("CAMPAIGN"), eq("CP20260902-000001"), anyString(), eq("CREATE"), contains("国庆满减活动"));
    }

    @Test
    void create_blank_name_rejected_400() {
        CampaignService.CampaignCmd cmd = new CampaignService.CampaignCmd(
                "  ", "FULL_REDUCE", List.of("抖音"), null, null, null, null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("活动名称不可为空"));
        verify(campaignRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void create_illegal_type_rejected_400() {
        CampaignService.CampaignCmd cmd = new CampaignService.CampaignCmd(
                "活动", "BOGUS", List.of("抖音"), null, null, null, null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("活动类型不合法"));
    }

    @Test
    void create_empty_channels_rejected_400() {
        CampaignService.CampaignCmd cmd = new CampaignService.CampaignCmd(
                "活动", "GIFT", List.of(), null, null, null, null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("请至少选择一个投放渠道"));
    }

    @Test
    void create_end_before_start_rejected_400() {
        CampaignService.CampaignCmd cmd = new CampaignService.CampaignCmd(
                "活动", "GIFT", List.of("抖音"),
                LocalDate.now().plusDays(10), LocalDate.now(), null, null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("活动结束日期不可早于开始日期"));
    }

    @Test
    void create_negative_budget_rejected_400() {
        CampaignService.CampaignCmd cmd = new CampaignService.CampaignCmd(
                "活动", "GIFT", List.of("抖音"), null, null, -1L, null, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("活动预算不可为负"));
    }

    @Test
    void create_negative_target_rejected_400() {
        CampaignService.CampaignCmd cmd = new CampaignService.CampaignCmd(
                "活动", "GIFT", List.of("抖音"), null, null, null, -1L, null, null, null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(cmd));
        assertTrue(ex.getReason().contains("目标成交额不可为负"));
    }

    // ==================== 状态流转 ====================

    @Test
    void transit_legal_draft_to_scheduled_returns_true_and_audits() {
        when(campaignRepo.findById("A1")).thenReturn(Optional.of(campaign("A1", "DRAFT")));
        boolean changed = service.transit("A1", "SCHEDULED");

        assertTrue(changed);
        ArgumentCaptor<Campaign> cap = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepo).save(cap.capture());
        assertEquals("SCHEDULED", cap.getValue().getStatus());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq("CAMPAIGN"), eq("A1"), anyString(), eq("TRANSIT"), payload.capture());
        assertTrue(payload.getValue().contains("\"from\":\"DRAFT\""));
        assertTrue(payload.getValue().contains("\"to\":\"SCHEDULED\""));
    }

    @Test
    void transit_full_lifecycle_draft_scheduled_running_ended() {
        Campaign c = campaign("A1", "DRAFT");
        when(campaignRepo.findById("A1")).thenReturn(Optional.of(c));

        assertTrue(service.transit("A1", "SCHEDULED"));
        assertEquals("SCHEDULED", c.getStatus());
        assertTrue(service.transit("A1", "RUNNING"));
        assertEquals("RUNNING", c.getStatus());
        assertTrue(service.transit("A1", "ENDED"));
        assertEquals("ENDED", c.getStatus());
        verify(audit, times(3)).record(eq("CAMPAIGN"), eq("A1"), anyString(), eq("TRANSIT"), anyString());
    }

    @Test
    void transit_same_state_is_idempotent_false_and_no_audit() {
        when(campaignRepo.findById("A1")).thenReturn(Optional.of(campaign("A1", "DRAFT")));
        boolean changed = service.transit("A1", "DRAFT");

        assertFalse(changed);
        verify(campaignRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void transit_unknown_target_rejected_400() {
        when(campaignRepo.findById("A1")).thenReturn(Optional.of(campaign("A1", "DRAFT")));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.transit("A1", "ARCHIVED"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("目标状态不合法"));
        verifyNoInteractions(audit);
    }

    @Test
    void transit_illegal_jump_draft_to_running_rejected_400_with_chinese_message() {
        when(campaignRepo.findById("A1")).thenReturn(Optional.of(campaign("A1", "DRAFT")));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.transit("A1", "RUNNING"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("草稿"));
        assertTrue(ex.getReason().contains("进行中"));
        verify(campaignRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void transit_from_terminal_ended_rejected_400() {
        when(campaignRepo.findById("A1")).thenReturn(Optional.of(campaign("A1", "ENDED")));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.transit("A1", "RUNNING"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("已结束"));
    }

    @Test
    void transit_cancel_from_draft_is_allowed() {
        when(campaignRepo.findById("A1")).thenReturn(Optional.of(campaign("A1", "DRAFT")));
        assertTrue(service.transit("A1", "CANCELLED"));
    }

    @Test
    void transit_not_found_404() {
        when(campaignRepo.findById("X")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.transit("X", "RUNNING"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(ex.getReason().contains("活动不存在"));
    }
}
