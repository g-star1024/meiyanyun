package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** M3 设置变更日志仓库。 */
public interface M3SettingsChangeLogRepository extends JpaRepository<M3SettingsChangeLog, Long> {

    /** 变更记录卡数据源：按时刻倒序取近 50 条。 */
    List<M3SettingsChangeLog> findTop50ByOrderByCreatedAtDesc();
}
