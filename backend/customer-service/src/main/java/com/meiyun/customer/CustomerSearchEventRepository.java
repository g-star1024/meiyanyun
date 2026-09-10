package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerSearchEventRepository extends JpaRepository<CustomerSearchEvent, Long> {

    /** 近线中继扫描：最早的待同步事件优先（FIFO，限批 50 条防长任务）。 */
    List<CustomerSearchEvent> findFirst50ByStatusOrderByEventIdAsc(String status);
}
