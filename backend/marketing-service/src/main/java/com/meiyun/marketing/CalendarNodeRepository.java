package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalendarNodeRepository extends JpaRepository<CalendarNode, String> {
    List<CalendarNode> findAllByOrderByNodeDateAsc();
}
