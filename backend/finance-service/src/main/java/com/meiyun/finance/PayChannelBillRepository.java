package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 渠道账单仓储（pay_channel_bill）。业务表 JPA 自动建表，无物理外键。
 */
public interface PayChannelBillRepository extends JpaRepository<PayChannelBill, String> {

    /** 某导入批次是否已存在（整批幂等：同 import_batch 重放不重复落行）。 */
    long countByImportBatch(String importBatch);

    /** 生成当日不重号账单行号：PCB + yyyyMMdd + - + 6 位序号（3 字符前缀，序号从第 13 位起，对齐 CCR 写法）。 */
    @Query(value = "select coalesce(max(cast(substring(bill_id from 13) as bigint)),0) "
            + "from pay_channel_bill where bill_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);

    /**
     * 生成当日不重号导入批次号：IMP + yyyyMMdd + - + 6 位序号（独立于 bill_id 序列，
     * 查 import_batch 列；同日第二次导入不会与首次批次号撞号而被误判幂等重放）。
     */
    @Query(value = "select coalesce(max(cast(substring(import_batch from 13) as bigint)),0) "
            + "from pay_channel_bill where import_batch like :prefix", nativeQuery = true)
    long maxBatchSeqOfDay(@Param("prefix") String prefix);

    /** 勾兑查询：时间区间（UTC 半开）内账单，按交易时间正序。 */
    List<PayChannelBill> findByBillTimeBetweenOrderByBillTimeAsc(OffsetDateTime from, OffsetDateTime to);
}
