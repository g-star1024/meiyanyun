package com.meiyun.finance;

import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * B50 卡4（L132）：经营目标列表三级归属行级数据域契约（JUnit5 + Mockito）。
 *
 * <p>种子树：G1 集团总目标 → G1-E 华东区 / G1-N 华北区 / G1-S 华南区，
 * 门店分解 T01-R=SST06 杭州西湖店、T02-R=SST01 上海徐汇店、T03-R=SST03 北京国贸店。
 * 口径：GROUP 行有登录即可见；REGION 行仅 REGION 域且 ownerName 含本域中文；
 * STORE 行按 JWT stores 名单收窄；GROUP/BRAND/超管/匿名全量。
 * 真正的网关 JWT 四角闭合由 curl + PG 三轨真验。
 */
@ExtendWith(MockitoExtension.class)
class BizTargetServiceTest {

    @Mock BizTargetRepository targetRepo;
    @Mock FinanceAuditRecorder audit;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @InjectMocks BizTargetService service;

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    void list_regionEast_seesOnlyGroupEastRegionAndEastStores() {
        when(targetRepo.findAllByOrderByIdAsc()).thenReturn(seedRows());
        SecurityContext.set(new LoginUser("E011", "冯区域经理", List.of("REGION_MGR"),
                null, "REGION", List.of("target:view"), false, "华东",
                List.of("SST01", "SST02", "SST06")));

        List<String> ids = visibleIds(service.list(null, null, null, null));

        assertTrue(ids.contains("G1"), "集团总目标各域共享");
        assertTrue(ids.contains("G1-E"), "华东区域行可见");
        assertTrue(ids.contains("T01-R"), "域内门店 SST06 可见");
        assertTrue(ids.contains("T02-R"), "域内门店 SST01 可见");
        assertFalse(ids.contains("G1-N"), "华北区域行不可见");
        assertFalse(ids.contains("G1-S"), "华南区域行不可见");
        assertFalse(ids.contains("G5"), "华南治疗人次行不可见");
        assertFalse(ids.contains("T03-R"), "域外门店 SST03 不可见");
        assertEquals(4, ids.size());
    }

    @Test
    void list_storePrincipal_seesGroupAndOwnStoreOnly() {
        when(targetRepo.findAllByOrderByIdAsc()).thenReturn(seedRows());
        SecurityContext.set(new LoginUser("E101", "上海徐汇店长", List.of("STORE_MGR"),
                "SST01", "STORE", List.of("target:view"), false, null, List.of("SST01")));

        List<String> ids = visibleIds(service.list(null, null, null, null));

        assertTrue(ids.contains("G1"));
        assertTrue(ids.contains("T02-R"), "本店 SST01 分解可见");
        assertFalse(ids.contains("T01-R"), "他店 SST06 不可见");
        assertFalse(ids.contains("T03-R"), "他店 SST03 不可见");
        assertFalse(ids.contains("G1-E"), "门店岗不见区域分解行");
        assertFalse(ids.contains("G1-N"));
        assertEquals(2, ids.size());
    }

    @Test
    void list_otherRegionRegionPrincipal_seesNoRegionRowsAndNoStores() {
        when(targetRepo.findAllByOrderByIdAsc()).thenReturn(seedRows());
        SecurityContext.set(new LoginUser("E031", "京区域经理", List.of("REGION_MGR"),
                null, "REGION", List.of("target:view"), false, "华北",
                List.of("SST03")));

        List<String> ids = visibleIds(service.list(null, null, null, null));

        assertTrue(ids.contains("G1"));
        assertTrue(ids.contains("G1-N"), "华北区域行可见");
        assertFalse(ids.contains("G1-E"));
        assertFalse(ids.contains("G1-S"));
        assertFalse(ids.contains("G5"), "G5 虽属华南区，名称不含华北不可见");
        assertTrue(ids.contains("T03-R"), "域内门店 SST03 可见");
        assertFalse(ids.contains("T01-R"));
        assertFalse(ids.contains("T02-R"));
        assertEquals(3, ids.size());
    }

    @Test
    void list_groupScopeAndAnonymous_seeEverything() {
        when(targetRepo.findAllByOrderByIdAsc()).thenReturn(seedRows());

        SecurityContext.set(new LoginUser("SE101", "超管", List.of("SUPER_ADMIN"),
                null, "GROUP", List.of("*"), false, null, List.of()));
        assertEquals(8, service.list(null, null, null, null).size(), "超管全量");

        SecurityContext.clear();
        assertEquals(8, service.list(null, null, null, null).size(), "匿名服务间通道全量");
    }

    @Test
    void list_ownerTypeFilterStillAppliedAfterScope() {
        when(targetRepo.findAllByOrderByIdAsc()).thenReturn(seedRows());
        SecurityContext.set(new LoginUser("E011", "冯区域经理", List.of("REGION_MGR"),
                null, "REGION", List.of("target:view"), false, "华东",
                List.of("SST01", "SST02", "SST06")));

        List<String> ids = visibleIds(service.list("REGION", null, null, null));

        assertEquals(List.of("G1-E"), ids, "数据域 + ownerType 双重过滤后仅剩华东区行");
    }

    private static List<String> visibleIds(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> String.valueOf(r.get("id"))).collect(Collectors.toList());
    }

    private static List<BizTarget> seedRows() {
        return List.of(
                row("G1", "GROUP", "美云集团", "GROUP"),
                row("G1-E", "R-EAST", "华东区", "REGION"),
                row("G1-N", "R-NORTH", "华北区", "REGION"),
                row("G1-S", "R-SOUTH", "华南区", "REGION"),
                row("T01-R", "SST06", "杭州西湖店", "STORE"),
                row("T02-R", "SST01", "上海徐汇店", "STORE"),
                row("T03-R", "SST03", "北京国贸店", "STORE"),
                row("G5", "R-SOUTH", "华南区", "REGION"));
    }

    private static BizTarget row(String targetId, String ownerId, String ownerName, String ownerType) {
        BizTarget t = new BizTarget();
        t.setTargetId(targetId);
        t.setOwnerId(ownerId);
        t.setOwnerName(ownerName);
        t.setOwnerType(ownerType);
        t.setMetric("REVENUE");
        t.setPeriod("YEAR");
        t.setPeriodLabel("2026年度");
        t.setApproval("APPROVED");
        return t;
    }
}
