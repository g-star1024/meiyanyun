package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FollowTaskRepository extends JpaRepository<FollowTask, Long> {

    List<FollowTask> findAllByOrderByCreatedAtDesc();

    Optional<FollowTask> findByFollowNo(String followNo);

    Optional<FollowTask> findTopByFollowNoLikeOrderByFollowNoDesc(String prefix);

    /** AI 下发幂等前置查重（idem_key 部分唯一索引）。 */
    Optional<FollowTask> findByIdemKey(String idemKey);
}
