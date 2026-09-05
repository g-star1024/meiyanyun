package com.meiyun.store.consumable;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 库存余量仓库（扣减走悲观行锁防超扣） */
public interface ConsumableStockRepository extends JpaRepository<ConsumableStock, Long> {

    Optional<ConsumableStock> findByConsumableId(Long consumableId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ConsumableStock s where s.consumableId = :consumableId")
    Optional<ConsumableStock> lockByConsumableId(@Param("consumableId") Long consumableId);
}
