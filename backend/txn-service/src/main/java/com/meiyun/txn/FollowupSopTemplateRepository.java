package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 术后 SOP 模板仓储（followup_sop_template）。
 */
public interface FollowupSopTemplateRepository extends JpaRepository<FollowupSopTemplate, String> {

    /** 全部启用模板（当前仅一个集团通用模板；排程取其节点）。 */
    List<FollowupSopTemplate> findByEnabledTrue();
}
