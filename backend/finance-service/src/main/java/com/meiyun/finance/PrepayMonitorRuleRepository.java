package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PrepayMonitorRuleRepository extends JpaRepository<PrepayMonitorRule, String> {

    List<PrepayMonitorRule> findByEnabledTrueOrderByCodeAsc();
}
