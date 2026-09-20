package com.meiyun.store.inspection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RectifyIssueRepository extends JpaRepository<RectifyIssue, Long> {

    List<RectifyIssue> findByInsIdOrderByIdAsc(Long insId);
}
