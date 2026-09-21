package com.meiyun.txn;

import java.time.OffsetDateTime;

/**
 * B83 卡2 L66 疗程跟踪详情弹层核销记录读 DTO（对齐前端 mock store 的 AssetTxn 结构）。
 *
 * <p>按 cardNo 倒序返回该卡全部核销流水（card_ledger/writeoff_record，约 304 条已补流水）。
 * 金额单位「分」，纯扣次为 0；operatorName 由 TxnStaffNameResolver 调 org-service 解析，
 * 服务不可用时降级返回 null（前端兜底用工号展示）。
 *
 * <p>核销状态 DONE=已核销；status 中文存储中文展示，不转英文码。
 */
public record WriteoffRecordDTO(
        String writeoffId,
        String cardNo,
        String project,
        Integer timesUsed,
        Long amount,
        String operator,
        String operatorName,
        OffsetDateTime createdAt,
        String status) {}
