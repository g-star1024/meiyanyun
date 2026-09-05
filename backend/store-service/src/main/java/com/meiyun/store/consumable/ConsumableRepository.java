package com.meiyun.store.consumable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 耗材档案仓库 */
public interface ConsumableRepository extends JpaRepository<Consumable, Long> {

    List<Consumable> findByStoreCodeOrderBySkuCodeAsc(String storeCode);

    Optional<Consumable> findByStoreCodeAndSkuCode(String storeCode, String skuCode);

    // 注意：JDBC 连接串带 stringtype=unspecified 时，null 的 String 命名参数没有类型上下文：
    // ① 直接进 lower(?) 会被推断为 bytea → function lower(bytea) does not exist；
    // ② 单独出现在「? is null」判空位 → could not determine data type of parameter。
    // 两处都要显式 cast(... as string)（Hibernate 渲染为 cast(? as text)）强制 varchar 绑定。
    @Query("select c from Consumable c where (cast(:storeCode as string) is null or c.storeCode = :storeCode) "
            + "and (cast(:category as string) is null or c.category = :category) "
            + "and (cast(:kw as string) is null or lower(c.name) like lower(cast(:kw as string)) "
            + "or lower(c.skuCode) like lower(cast(:kw as string))) "
            + "order by c.skuCode asc")
    List<Consumable> search(@Param("storeCode") String storeCode,
                            @Param("category") String category,
                            @Param("kw") String keyword);
}
