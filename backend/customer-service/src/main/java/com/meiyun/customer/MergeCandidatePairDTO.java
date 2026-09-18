package com.meiyun.customer;

import java.time.Instant;
import java.util.List;

/**
 * 撞单合并候选对（期1 只读读模型）：两个归一化手机号相同的有效客户组成一对。
 *
 * <p>groupType 分组语义：
 * <ul>
 *   <li>SAME_STORE：两侧均有门店且同店；</li>
 *   <li>POOL：两侧均无门店（公海撞单，仅超管可见，数据域层强制）；</li>
 *   <li>CROSS_STORE：跨店，或有门店/公海混合。</li>
 * </ul>
 * 期1 仅手机号精确归一一条命中理由，score 取确定性常量（不伪造算法分）；
 * 手机号一律掩码（前3后4），不回显归一化明文。
 */
public record MergeCandidatePairDTO(
        String pairId,
        String groupType,
        List<String> matchReasons,
        double score,
        Side sideA,
        Side sideB
) {
    public record Side(
            String customerId,
            String name,
            String maskPhone,
            String level,
            String storeCode,
            String storeName,
            String ownerStaffId,
            String ownerStaffName,
            Instant createdAt
    ) {
    }
}
