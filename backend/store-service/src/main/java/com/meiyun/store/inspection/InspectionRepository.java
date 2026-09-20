package com.meiyun.store.inspection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InspectionRepository extends JpaRepository<Inspection, Long>,
        JpaSpecificationExecutor<Inspection> {

    @Query(value = "select coalesce(max(cast(substring(ins_no from 14) as bigint)),0) "
            + "from inspection where ins_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
