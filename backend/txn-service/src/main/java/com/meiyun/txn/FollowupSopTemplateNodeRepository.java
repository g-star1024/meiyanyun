package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 术后 SOP 模板节点仓储（followup_sop_template_node）。
 */
public interface FollowupSopTemplateNodeRepository extends JpaRepository<FollowupSopTemplateNode, Long> {

    /** 某模板的启用节点，按行号升序（排程实际使用）。 */
    List<FollowupSopTemplateNode> findByTemplateNoAndEnabledTrueOrderByLineNoAsc(String templateNo);

    /** 某模板的全部节点（含停用，模板编排页使用），按行号升序。 */
    List<FollowupSopTemplateNode> findByTemplateNoOrderByLineNoAsc(String templateNo);

    /** 删除某模板的全部节点（恢复默认前清空）。 */
    void deleteByTemplateNo(String templateNo);
}
