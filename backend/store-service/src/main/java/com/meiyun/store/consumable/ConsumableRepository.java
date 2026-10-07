package com.meiyun.store.consumable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 耗材档案仓库。
 *
 * <p>列表查询走 {@link JpaSpecificationExecutor}，由 Service 以 {@code DataScope.storeSpec}
 * 强制叠加当前登录人数据域（B50 卡8：原 {@code cast(:storeCode as string) is null} 写法在
 * REGION 多店账号无参时退化为全量，区域经理可拉全部门店耗材台账，存在跨区越权）。
 */
public interface ConsumableRepository extends JpaRepository<Consumable, Long>,
        JpaSpecificationExecutor<Consumable> {

    Optional<Consumable> findByStoreCodeAndSkuCode(String storeCode, String skuCode);

    /** 本店 HC- 号段最大 SKU 码（只取自动号段），供 CodeGen 门店内续号。 */
    @Query("select max(c.skuCode) from Consumable c where c.storeCode = :storeCode and c.skuCode like 'HC-%'")
    String maxHcCode(@Param("storeCode") String storeCode);
}
