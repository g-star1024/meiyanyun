package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FinAssetRepository extends JpaRepository<FinAsset, String> {

    List<FinAsset> findByStatusOrderByAssetIdAsc(String status);

    List<FinAsset> findByStoreCodeAndStatusOrderByAssetIdAsc(String storeCode, String status);

    /**
     * 资产号当日最大序号（asset_id 形如 FA20260906-000001：2 位前缀 + 8 位日期 + 1 个连字符，
     * 序号从第 12 位起 6 位）。
     */
    @Query(value = "select coalesce(max(cast(substring(asset_id from 12) as bigint)), 0) " +
           "from fin_asset where asset_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
