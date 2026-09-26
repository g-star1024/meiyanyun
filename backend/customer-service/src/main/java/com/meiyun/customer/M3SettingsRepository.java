package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

/** M3 客户域设置单例仓库（仅 id=1 一行）。 */
public interface M3SettingsRepository extends JpaRepository<M3Settings, Integer> {
}
