package com.meiyun.store.procurement;

import com.meiyun.store.StoreRepository;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import com.meiyun.store.consumable.ConsumableService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B50 卡2：采购列表数据域收窄契约（JUnit5 + Mockito）。
 *
 * <p>越界哨兵 {@code "__NONE__"} 必须短路为空列表、不触库；正常/无参路径必须走
 * {@code findAll(Specification, Sort id desc)}（由 DataScope.storeSpec 强制叠加数据域），
 * 不得再退回无数据域的全量查询。真正的 REGION 跨区谓词闭合由 curl 经网关 + PG 三轨真验。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock SupplierRepository supplierRepo;
    @Mock PurchaseOrderRepository poRepo;
    @Mock PurchaseOrderItemRepository itemRepo;
    @Mock GoodsReceiptRepository receiptRepo;
    @Mock StoreRepository storeRepo;
    @Mock PoNoGenerator poNoGenerator;
    @Mock ConsumableService consumableService;
    @Mock ConsumableAuditRecorder audit;
    @InjectMocks PurchaseOrderService service;

    @AfterEach
    void clearContext() {
        com.meiyun.security.SecurityContext.clear();
    }

    @Test
    void list_noneSentinelShortCircuitsWithoutDb() {
        List<Map<String, Object>> out = service.listPurchaseOrders("__NONE__", null);

        assertTrue(out.isEmpty());
        verifyNoInteractions(poRepo);
        verifyNoInteractions(itemRepo);
    }

    @Test
    void list_regionNoParam_usesSpecFindAllOrderedByIdDesc() {
        when(poRepo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        service.listPurchaseOrders(null, null);

        verify(poRepo).findAll(any(Specification.class),
                eq(Sort.by(Sort.Direction.DESC, "id")));
    }

    @Test
    void list_statusAndStorePassed_neverFallsBackToUnscopedQuery() {
        when(poRepo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());

        service.listPurchaseOrders("SST01", "APPROVED");

        verify(poRepo).findAll(any(Specification.class),
                eq(Sort.by(Sort.Direction.DESC, "id")));
        verify(poRepo, never()).findAll();
    }
}
