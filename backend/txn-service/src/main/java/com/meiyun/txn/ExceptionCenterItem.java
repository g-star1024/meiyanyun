package com.meiyun.txn;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 统一异常中心只读单据（B63 卡2 L83）：收口三源为一个读模型，前端异常工作台零写操作。
 *
 * <ul>
 *   <li>{@code source}：BOM_DEDUCT（bom_deduct_exception）/ WRITEOFF（writeoff_record ABNORMAL）
 *       / FIN_ABNORMAL（fin_abnormal_bill，跨服务只读归集）；</li>
 *   <li>{@code id}：带源前缀的统一 ID——BOM:BEX... / WO:WO... / FIN:AB...，详情接口据此分发；</li>
 *   <li>{@code type}：统一业务类型 BUSINESS（前端四枚举 SYSTEM/BUSINESS/DEVICE/COMPLAINT 不动，
 *       源差异由 source 标签承载）；</li>
 *   <li>{@code status}：统一三态 PENDING/PROCESSING/CLOSED；</li>
 *   <li>{@code level}：HIGH/MEDIUM/LOW；</li>
 *   <li>{@code disposeRoute}：源处置视图路由，中心仅跳转不新建处置链。</li>
 * </ul>
 */
public record ExceptionCenterItem(String id, String no, String source, String storeCode, String storeName,
                                  String type, String level, String title, String description,
                                  String status, String assignee, OffsetDateTime occurredAt,
                                  OffsetDateTime closedAt, List<Node> timeline, String disposeRoute) {

    /** 时间线节点（对齐前端 {by,text,at} 消费契约）。 */
    public record Node(String by, String text, OffsetDateTime at) {
    }
}
