package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CardFinanceEventRepository extends JpaRepository<CardFinanceEvent, Long> {

    /** 定时投递扫描：最早的待投递事件优先（FIFO，限批 50 条防长事务）。 */
    List<CardFinanceEvent> findFirst50ByStatusOrderByEventIdAsc(String status);
}
