package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataLineageEdgeRepository extends JpaRepository<DataLineageEdge, Long> {

    /** 边列表按 id 升序（种子插入序=前端 mock 数组序）。 */
    List<DataLineageEdge> findAllByOrderByIdAsc();
}
