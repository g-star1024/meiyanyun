package com.meiyun.store.pricelist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StorePriceRepository extends JpaRepository<StorePrice, Long> {

    Optional<StorePrice> findByStoreCodeAndSku(String storeCode, String sku);

    List<StorePrice> findByStoreCodeOrderBySku(String storeCode);

    List<StorePrice> findAllByOrderByStoreCodeAscSkuAsc();

    long countByStoreCode(String storeCode);
}
