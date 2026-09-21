package com.meiyun.store.acquisition;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AcquisitionRepository extends JpaRepository<AcquisitionCampaign, Long>,
        JpaSpecificationExecutor<AcquisitionCampaign> {

    @Query(value = "select coalesce(max(cast(substring(aq_no from 13) as bigint)),0) "
            + "from acquisition_campaign where aq_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
