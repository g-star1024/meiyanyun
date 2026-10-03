package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PosterRecordRepository extends JpaRepository<PosterRecord, String> {

    List<PosterRecord> findAllByOrderByCreatedAtDesc();

    Optional<PosterRecord> findTopByPosterIdLikeOrderByPosterIdDesc(String like);

    /** 漏斗四级原子自增（L160②：@Modifying UPDATE 防 lost update，与采集同事务）。 */
    @Modifying
    @Query("UPDATE PosterRecord p SET p.share = p.share + 1 WHERE p.posterId = :posterId")
    int bumpShare(@Param("posterId") String posterId);

    @Modifying
    @Query("UPDATE PosterRecord p SET p.scan = p.scan + 1 WHERE p.posterId = :posterId")
    int bumpScan(@Param("posterId") String posterId);

    @Modifying
    @Query("UPDATE PosterRecord p SET p.lead = p.lead + 1 WHERE p.posterId = :posterId")
    int bumpLead(@Param("posterId") String posterId);

    @Modifying
    @Query("UPDATE PosterRecord p SET p.visit = p.visit + 1 WHERE p.posterId = :posterId")
    int bumpVisit(@Param("posterId") String posterId);
}
