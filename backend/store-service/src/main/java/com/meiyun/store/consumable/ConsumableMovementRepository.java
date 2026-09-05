package com.meiyun.store.consumable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 出入库流水仓库 */
public interface ConsumableMovementRepository extends JpaRepository<ConsumableMovement, Long> {

    List<ConsumableMovement> findByStoreCodeOrderByIdDesc(String storeCode);

    List<ConsumableMovement> findByConsumableIdOrderByIdDesc(Long consumableId);

    /** 幂等：同一业务单 + 同一 SKU 的流水已存在则视为已处理（防重试双扣/双入） */
    Optional<ConsumableMovement> findFirstByBizRefAndConsumableId(String bizRef, Long consumableId);

    // cast 原因同 ConsumableRepository：stringtype=unspecified 下 null 命名参数无类型上下文，
    // 「? is null」判空位 PG 报 could not determine data type of parameter。
    @Query("select m from ConsumableMovement m where (cast(:storeCode as string) is null "
            + "or m.storeCode = :storeCode) "
            + "and m.moveType in :types order by m.id desc")
    List<ConsumableMovement> searchMovements(@Param("storeCode") String storeCode,
                                             @Param("types") List<String> types);
}
