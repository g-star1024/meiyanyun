package com.meiyun.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * /api/stores/internal/first 排序契约单测（JUnit5 + Mockito）：
 * 必须走 findFirstByOrderByStoreCodeAsc（store_code 升序首家），
 * 不得退回无 ORDER BY 的 findAll() 物理顺序；空库返回空 Map 由调用方降级。
 */
@ExtendWith(MockitoExtension.class)
class StoreControllerTest {

    @Mock
    StoreRepository storeRepository;
    @Mock
    RegionDistRepository regionDistRepository;
    @InjectMocks
    StoreController controller;

    private Store store(String code, String name) {
        Store s = new Store();
        s.setStoreCode(code);
        s.setStoreName(name);
        return s;
    }

    @Test
    void firstStore_returnsOrderedFirstCodeAndName() {
        when(storeRepository.findFirstByOrderByStoreCodeAsc())
                .thenReturn(Optional.of(store("ST-BJ-001", "北京朝阳店")));

        Map<String, String> out = controller.firstStore();

        assertEquals(2, out.size());
        assertEquals("ST-BJ-001", out.get("code"));
        assertEquals("北京朝阳店", out.get("name"));
        verify(storeRepository).findFirstByOrderByStoreCodeAsc();
    }

    @Test
    void firstStore_neverReliesOnUnorderedFindAll() {
        when(storeRepository.findFirstByOrderByStoreCodeAsc())
                .thenReturn(Optional.of(store("SST01", "上海徐汇店")));

        controller.firstStore();

        verify(storeRepository, never()).findAll();
        verify(storeRepository).findFirstByOrderByStoreCodeAsc();
    }

    @Test
    void firstStore_emptyTableReturnsEmptyMap() {
        when(storeRepository.findFirstByOrderByStoreCodeAsc()).thenReturn(Optional.empty());

        Map<String, String> out = controller.firstStore();

        assertTrue(out.isEmpty());
    }
}
