package com.meiyun.store.consumable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 出入库流水仓库。
 *
 * <p>列表查询走 {@link JpaSpecificationExecutor}，由 Service 以 {@code DataScope.storeSpec}
 * 强制叠加当前登录人数据域（B50 卡8：与档案列表同型，原 null 门店参退化为全量的跨区越权）。
 */
public interface ConsumableMovementRepository extends JpaRepository<ConsumableMovement, Long>,
        JpaSpecificationExecutor<ConsumableMovement> {

    List<ConsumableMovement> findByConsumableIdOrderByIdDesc(Long consumableId);

    /** 幂等：同一业务单 + 同一 SKU 的流水已存在则视为已处理（防重试双扣/双入） */
    Optional<ConsumableMovement> findFirstByBizRefAndConsumableId(String bizRef, Long consumableId);
}
