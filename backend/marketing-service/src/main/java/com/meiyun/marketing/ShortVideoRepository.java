package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShortVideoRepository extends JpaRepository<ShortVideo, String> {

    List<ShortVideo> findAllByOrderByPublishedAtDesc();

    Optional<ShortVideo> findTopByVideoIdLikeOrderByVideoIdDesc(String like);
}
