package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LiveSessionRepository extends JpaRepository<LiveSession, String> {

    List<LiveSession> findAllByOrderByStartTimeDesc();

    Optional<LiveSession> findTopBySessionIdLikeOrderBySessionIdDesc(String like);
}
