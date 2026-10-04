package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceRepository extends JpaRepository<DataSource, Long> {

    boolean existsByCode(String code);

    List<DataSource> findAllByOrderByIdAsc();
}
