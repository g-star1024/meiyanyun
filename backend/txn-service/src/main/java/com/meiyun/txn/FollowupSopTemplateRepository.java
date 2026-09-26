package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 术后 SOP 模板仓储（followup_sop_template）。
 */
public interface FollowupSopTemplateRepository extends JpaRepository<FollowupSopTemplate, String> {

    /** 全部启用模板（当前仅一个集团通用模板；排程取其节点）。 */
    List<FollowupSopTemplate> findByEnabledTrue();

    /** 本店启用模板，创建时间倒序（排程「本店最新优先」取第一个有启用节点的模板）。 */
    List<FollowupSopTemplate> findByStoreCodeAndEnabledTrueOrderByCreatedAtDesc(String storeCode);

    /** 集团通用启用模板，创建时间正序（排程回落：默认种子模板最早创建，最先命中）。 */
    List<FollowupSopTemplate> findByStoreCodeIsNullAndEnabledTrueOrderByCreatedAtAsc();

    /** 门店模板号号池：SPT + yyyyMMdd + - + 6 位序号（substring from 13，char_length=18），仿批次号范式。 */
    @Query(value = "select coalesce(max(cast(substring(template_no from 13) as integer)),0) "
            + "from followup_sop_template where template_no like ?1 and char_length(template_no) = 18",
            nativeQuery = true)
    long maxSeqOfDay(String prefix);
}
