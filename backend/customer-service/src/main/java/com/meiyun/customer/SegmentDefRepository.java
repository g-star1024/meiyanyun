package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** segment_def 数据访问（M3-B3）；号池/幂等查询照 NpsRecord 先例。 */
public interface SegmentDefRepository extends JpaRepository<SegmentDef, Long> {

    Optional<SegmentDef> findBySegmentNo(String segmentNo);

    /** 同名幂等（store_code NULL 与 '' 视为同一全连锁槽位，由调用方归一后查询）。 */
    @Query("SELECT s FROM SegmentDef s WHERE s.name = :name AND COALESCE(s.storeCode, '*') = COALESCE(:storeCode, '*')")
    Optional<SegmentDef> findByNameAndStore(@Param("name") String name, @Param("storeCode") String storeCode);

    Optional<SegmentDef> findByClientToken(String clientToken);

    List<SegmentDef> findBySourceProfileId(Long sourceProfileId);

    /** 号池：库内最大 SG 号（种子 SG0001-0003 占号段，应用层递增）。 */
    @Query("SELECT MAX(s.segmentNo) FROM SegmentDef s WHERE s.segmentNo LIKE 'SG%'")
    String maxSegmentNo();
}
