package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PosterRecordRepository extends JpaRepository<PosterRecord, String> {

    List<PosterRecord> findAllByOrderByCreatedAtDesc();

    Optional<PosterRecord> findTopByPosterIdLikeOrderByPosterIdDesc(String like);
}
