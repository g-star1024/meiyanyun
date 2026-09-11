package com.meiyun.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface CustomerSearchEventRepository extends JpaRepository<CustomerSearchEvent, Long>,
        JpaSpecificationExecutor<CustomerSearchEvent> {

    /** 中继任务：FIFO 扫描最早 50 条待投递事件（处置台不动本方法）。 */
    List<CustomerSearchEvent> findFirst50ByStatusOrderByEventIdAsc(String status);

    Page<CustomerSearchEvent> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);
}
