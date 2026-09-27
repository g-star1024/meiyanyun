package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataServiceRepository extends JpaRepository<DataService, Long> {

    /** id 升序（种子插入序=前端 mock 数组序，id 自增天然保序）。 */
    List<DataService> findAllByOrderByIdAsc();
}
