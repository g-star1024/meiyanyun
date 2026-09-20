package com.meiyun.store.wastage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WastageRepository extends JpaRepository<Wastage, Long>,
        JpaSpecificationExecutor<Wastage> {

    Optional<Wastage> findByWsNo(String wsNo);

    @Query(value = "select coalesce(max(cast(substring(ws_no from 13) as bigint)), 0) "
            + "from wastage where ws_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
