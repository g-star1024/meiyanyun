package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 术后 SOP 模板节点仓储（followup_sop_template_node）。
 */
public interface FollowupSopTemplateNodeRepository extends JpaRepository<FollowupSopTemplateNode, Long> {

    /** 某模板的启用节点，按行号升序（排程实际使用）。 */
    List<FollowupSopTemplateNode> findByTemplateNoAndEnabledTrueOrderByLineNoAsc(String templateNo);
}
