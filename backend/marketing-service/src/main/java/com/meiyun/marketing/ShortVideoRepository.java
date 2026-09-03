package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShortVideoRepository extends JpaRepository<ShortVideo, String> {

    List<ShortVideo> findAllByOrderByPublishedAtDesc();
}
