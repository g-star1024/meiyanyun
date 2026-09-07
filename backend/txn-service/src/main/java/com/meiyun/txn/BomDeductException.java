package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * BOM 自动扣料异常登记（B10，DESIGN §6.2）：双签划扣 DONE 后按项目配方自动扣库失败时留痕。
 *
 * <p>医疗红线：扣料失败<b>不阻断、不回滚划扣</b>——划扣事务已提交，仅登记本表供门店追溯处理
 * （补货后「重试」成功 → RESOLVED；或走领用审批手工补单后「标记已处理」）。
 * 一个划扣单最多一条（UNIQUE(writeoff_id)），重试成功置 RESOLVED。
 */
@Entity
@Table(name = "bom_deduct_exception",
        uniqueConstraints = @UniqueConstraint(name = "uk_bom_exc_writeoff",
                columnNames = {"writeoff_id"}))
@Getter
@Setter
@NoArgsConstructor
public class BomDeductException {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_RESOLVED = "RESOLVED";

    /** 异常单号：BEX + yyyyMMdd + - + 6 位 */
    @Id
    @Column(name = "exc_id", length = 24)
    private String excId;

    /** 划扣单号（WO...，幂等键：一个划扣单最多一条异常） */
    @Column(name = "writeoff_id", nullable = false, length = 24)
    private String writeoffId;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(name = "project_name", length = 64)
    private String projectName;

    /** 中文原因（库存不足 / SKU 未建档 / 库存服务不可用等；BOM 未配置不登记，静默跳过） */
    @Column(nullable = false, length = 256)
    private String reason;

    /** 失败行明细 JSON：[{skuCode,needQty,stockQty}]（可空，服务不可用时无行明细） */
    @Column(name = "detail_json", length = 2048)
    private String detailJson;

    /** PENDING 待处理 / RESOLVED 已处理 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 失败次数（首次登记 + 每次重试失败累加；重试成功转 RESOLVED） */
    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolved_by", length = 32)
    private String resolvedBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = ST_PENDING;
    }
}
