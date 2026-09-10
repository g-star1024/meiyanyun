package com.meiyun.txn;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 病历模板仓储（emr_template）。
 */
public interface EmrTemplateRepository extends JpaRepository<EmrTemplate, String> {

    /**
     * 套用候选：启用模板 +（集团通用 store_code IS NULL 或本店自建）；type 为空时不过滤，
     * 传值时匹配「类型通用 NULL + 指定类型」。集团模板排前、组内按更新时间倒序。
     */
    @Query("select t from EmrTemplate t where t.enabled = true "
            + "and (t.storeCode is null or t.storeCode = :storeCode) "
            + "and (:type is null or t.type is null or t.type = :type) "
            + "order by t.storeCode desc, t.updatedAt desc")
    Page<EmrTemplate> searchActive(@Param("storeCode") String storeCode,
                                   @Param("type") String type, Pageable pageable);

    /**
     * 生成当日不重模板号：EMT + yyyyMMdd + - + 6 位序号（基础件号 char_length=18）。
     */
    @Query(value = "select coalesce(max(cast(substring(template_no from 13) as bigint)),0) "
            + "from emr_template where template_no like :prefix and char_length(template_no) = 18",
            nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
