package com.meiyun.store.bom;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 项目用料配方仓库 */
public interface ProjectBomRepository extends JpaRepository<ProjectBom, String> {

    /** 某项目在某门店 + 集团模板下的全部启用行（门店行与集团行一并取出，解析时按 SKU 门店优先去重）。 */
    @Query("select b from ProjectBom b where b.enabled = true and b.projectName = :projectName "
            + "and (b.storeCode = :storeCode or b.storeCode = '') order by b.bomId asc")
    List<ProjectBom> findEnabledForMatch(@Param("projectName") String projectName,
                                         @Param("storeCode") String storeCode);

    /** 配方维护：按项目（可选）+ 门店域（可选，空串=集团模板）列出，最近修改在前。 */
    @Query("select b from ProjectBom b where (cast(:projectName as string) is null "
            + "or b.projectName = :projectName) "
            + "and (cast(:storeCode as string) is null or b.storeCode = :storeCode) "
            + "order by b.projectName asc, b.storeCode asc, b.skuCode asc")
    List<ProjectBom> search(@Param("projectName") String projectName,
                            @Param("storeCode") String storeCode);

    /** 同项目+门店（空串=集团）+SKU 唯一行：upsert 定位用。 */
    Optional<ProjectBom> findByProjectNameAndStoreCodeAndSkuCode(String projectName,
                                                                 String storeCode, String skuCode);

    /**
     * 当日配方行号最大序号（bom_id 形如 BOM20260906-000007）。
     *
     * <p>BOM 为 <b>3 字母</b>前缀，连字符落在第 12 位，序号须从第 <b>13</b> 位起 6 位——
     * 与 BEX 同源修正，避免取到 {@code "-000007"} 后 cast 成负数导致序号回退撞键。
     */
    @Query(value = "select coalesce(max(cast(substring(bom_id from 13) as bigint)), 0) "
            + "from project_bom where bom_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
