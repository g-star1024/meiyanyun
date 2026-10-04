package com.meiyun.customer;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TagFactoryResultRepository extends JpaRepository<TagFactoryResult, Long> {

    List<TagFactoryResult> findByFactoryId(Long factoryId);

    long countByFactoryId(Long factoryId);

    @Modifying
    @Query("delete from TagFactoryResult r where r.factoryId = :factoryId")
    int deleteByFactoryId(@Param("factoryId") Long factoryId);
}
