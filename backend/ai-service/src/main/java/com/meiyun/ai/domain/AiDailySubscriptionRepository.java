package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiDailySubscriptionRepository extends JpaRepository<AiDailySubscription, Long> {

    Optional<AiDailySubscription> findByStaffId(String staffId);
}
