package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketingAssetRepository extends JpaRepository<MarketingAsset, String> {

    Optional<MarketingAsset> findTopByAssetIdLikeOrderByAssetIdDesc(String like);
}
