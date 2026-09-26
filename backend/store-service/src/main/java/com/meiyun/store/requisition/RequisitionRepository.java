package com.meiyun.store.requisition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RequisitionRepository extends JpaRepository<Requisition, Long>,
        JpaSpecificationExecutor<Requisition> {

    Optional<Requisition> findByRqNo(String rqNo);

    Optional<Requisition> findFirstByStoreCodeAndSourceTypeAndSourceRef(String storeCode, String sourceType, String sourceRef);

    @Query(value = "select coalesce(max(cast(substring(rq_no from 13) as bigint)), 0) "
            + "from requisition where rq_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
