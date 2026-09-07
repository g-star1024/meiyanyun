package com.meiyun.store.equipment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {

    Optional<Equipment> findByStoreCodeAndAssetNo(String storeCode, String assetNo);

    long countByStoreCode(String storeCode);

    /**
     * 设备台账检索：门店必过滤；category/status 为 ALL/空时不过滤；
     * keyword 模糊匹配资产编号/名称/品牌/型号/位置（大小写不敏感）。
     * stringtype=unspecified 下 null 参数须 cast(:x as string)，否则被推断为 bytea。
     */
    @Query("select e from Equipment e where e.storeCode = :storeCode "
            + "and (cast(:category as string) is null or e.category = cast(:category as string)) "
            + "and (cast(:status as string) is null or e.status = cast(:status as string)) "
            + "and (cast(:keyword as string) is null "
            + "     or lower(e.assetNo) like lower(concat('%', cast(:keyword as string), '%')) "
            + "     or lower(e.name) like lower(concat('%', cast(:keyword as string), '%')) "
            + "     or lower(coalesce(e.brand, '')) like lower(concat('%', cast(:keyword as string), '%')) "
            + "     or lower(coalesce(e.model, '')) like lower(concat('%', cast(:keyword as string), '%')) "
            + "     or lower(e.location) like lower(concat('%', cast(:keyword as string), '%'))) "
            + "order by e.assetNo")
    List<Equipment> search(@Param("storeCode") String storeCode,
                           @Param("category") String category,
                           @Param("status") String status,
                           @Param("keyword") String keyword);
}
