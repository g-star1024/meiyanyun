package com.meiyun.store.consumable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B50 卡8：耗材台账/流水列表数据域收窄契约（JUnit5 + Mockito）。
 *
 * <p>与 L127 采购列表同型：越界哨兵 {@code "__NONE__"} 必须短路为空列表、不触库；正常/无参路径
 * 必须走 {@code findAll(Specification, Sort)}（由 DataScope.storeSpec 强制叠加数据域），
 * 档案按 skuCode 升序、流水按 id 降序，不得再退回无数据域的全量查询。
 * 真正的 REGION 跨区谓词闭合由 curl 经网关 + PG 三轨真验。
 */
@ExtendWith(MockitoExtension.class)
class ConsumableServiceScopeTest {

    @Mock ConsumableRepository consumableRepo;
    @Mock ConsumableStockRepository stockRepo;
    @Mock ConsumableMovementRepository movementRepo;
    @Mock ConsumableAuditRecorder audit;
    @InjectMocks ConsumableService service;

    @AfterEach
    void clearContext() {
        com.meiyun.security.SecurityContext.clear();
    }

    @Test
    void list_noneSentinelShortCircuitsWithoutDb() {
        List<java.util.Map<String, Object>> out = service.listConsumables("__NONE__", null, null);

        assertTrue(out.isEmpty());
        verifyNoInteractions(consumableRepo);
        verifyNoInteractions(stockRepo);
    }

    @Test
    void list_regionNoParam_usesSpecFindAllOrderedBySkuCode() {
        when(consumableRepo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        service.listConsumables(null, null, null);

        verify(consumableRepo).findAll(any(Specification.class),
                eq(Sort.by(Sort.Direction.ASC, "skuCode")));
        verify(consumableRepo, never()).findAll();
    }

    @Test
    void list_categoryAndKeywordPassed_neverFallsBackToUnscopedQuery() {
        when(consumableRepo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        service.listConsumables("SST01", "敷料", "手套");

        verify(consumableRepo).findAll(any(Specification.class),
                eq(Sort.by(Sort.Direction.ASC, "skuCode")));
        verify(consumableRepo, never()).findAll();
    }

    @Test
    void movements_noneSentinelShortCircuitsWithoutDb() {
        List<java.util.Map<String, Object>> out = service.listMovements("__NONE__", null);

        assertTrue(out.isEmpty());
        verifyNoInteractions(movementRepo);
    }

    @Test
    void movements_regionNoParam_usesSpecFindAllOrderedByIdDesc() {
        when(movementRepo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        service.listMovements(null, null);

        verify(movementRepo).findAll(any(Specification.class),
                eq(Sort.by(Sort.Direction.DESC, "id")));
        verify(movementRepo, never()).findAll();
    }

    @Test
    void movements_explicitStoreAndTypes_neverFallsBackToUnscopedQuery() {
        when(movementRepo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        service.listMovements("SST01", List.of("PURCHASE", "USE"));

        verify(movementRepo).findAll(any(Specification.class),
                eq(Sort.by(Sort.Direction.DESC, "id")));
        verify(movementRepo, never()).findAll();
    }
}
