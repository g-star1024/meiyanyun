package com.meiyun.store.handover;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HandoverRepository extends JpaRepository<Handover, Long>, JpaSpecificationExecutor<Handover> {

    @Query(value = "select coalesce(max(cast(substring(ho_no from 13) as bigint)), 0) from handover where ho_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
