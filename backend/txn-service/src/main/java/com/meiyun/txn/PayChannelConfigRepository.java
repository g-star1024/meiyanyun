package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 支付渠道配置仓储（pay_channel_config）。业务表 JPA 自动建表，无物理外键。
 */
public interface PayChannelConfigRepository extends JpaRepository<PayChannelConfig, String> {

    /** 按渠道 + 门店定位配置（upsert 唯一键；store_code 空串 = 集团默认模板）。 */
    Optional<PayChannelConfig> findByChannelCodeAndStoreCode(String channelCode, String storeCode);

    /** 全部配置（按渠道、门店排序，列表富化用）。 */
    List<PayChannelConfig> findAllByOrderByChannelCodeAscStoreCodeAsc();

    /** 生成当日不重号配置号：PCC + yyyyMMdd + - + 6 位序号（3 字符前缀，序号从第 13 位起，对齐 CCR 写法）。 */
    @Query(value = "select coalesce(max(cast(substring(config_id from 13) as bigint)),0) "
            + "from pay_channel_config where config_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
