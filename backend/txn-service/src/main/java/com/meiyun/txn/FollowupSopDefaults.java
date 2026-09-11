package com.meiyun.txn;

import java.util.List;

/**
 * 术后随访 SOP 唯一默认事实源（P5-B31）：集团通用模板号/名称 + 内置四节点定义。
 *
 * <p>消除三处漂移：排程器空模板回退（{@link FollowupScheduler}）、种子播种
 * （{@link FollowupSopTemplateDataInitializer}）、模板管理「恢复默认」
 * （{@link FollowupSopTemplateService}）全部引用本类；前端 DEFAULT_POST_OP_SOP 亦保持一致。</p>
 */
public final class FollowupSopDefaults {

    /** 集团通用模板号（NULL 门店=全门店可用）；与种子 SPT-SEED-001 一致。 */
    public static final String DEFAULT_TEMPLATE_NO = "SPT-SEED-001";
    public static final String DEFAULT_TEMPLATE_NAME = "术后通用随访 SOP";

    /** 内置四阶段（不可删除，可停用/改天数改方式）：术后 1/3/7/30 天。 */
    public static final List<NodeDef> NODES = List.of(
            new NodeDef("CARE_24H", "术后 24h 关怀", 1, "WECHAT"),
            new NodeDef("FOLLOWUP_3D", "第 3 天回访", 3, "PHONE"),
            new NodeDef("RECOVERY_7D", "第 7 天恢复评估", 7, "WECHAT"),
            new NodeDef("REVISIT_30D", "第 30 天复诊提醒", 30, "PHONE"));

    private FollowupSopDefaults() {}

    /** 节点定义（与排程器 NodeDef 同形）。 */
    public record NodeDef(String stage, String label, int dayOffset, String method) {}
}
