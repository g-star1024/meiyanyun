package com.meiyun.store.reactivate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReactivateRepository extends JpaRepository<ReactivateCustomer, Long>,
        JpaSpecificationExecutor<ReactivateCustomer> {

    @Query(value = "select coalesce(max(cast(substring(rc_no from 13) as bigint)),0) "
            + "from reactivate where rc_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
