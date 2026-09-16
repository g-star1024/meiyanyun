package com.meiyun.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 报表生成任务（report_job，B49 卡11）：CSV 真实内容落 content（BYTEA）——
 * 容器文件系统不持久，DB 持久卷保下载可重放不伪造；历史种子行 content=NULL（不伪造，
 * 下载走 404「历史文件未留存，请重新生成」提示路径）。
 */
@Entity
@Table(name = "report_job")
@Getter @Setter @NoArgsConstructor
public class ReportJob {

    @Id
    @Column(name = "id", length = 8)
    private String id;

    @Column(name = "template_id", nullable = false, length = 8)
    private String templateId;

    @Column(name = "template_name", nullable = false, length = 64)
    private String templateName;

    @Column(name = "category", nullable = false, length = 16)
    private String category;

    /** 期段串：日报 'yyyy-MM-dd'、月报 'yyyy-MM'（历史任务照 mock 原值）。 */
    @Column(name = "period", nullable = false, length = 16)
    private String period;

    /** READY / GENERATING / FAILED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** CSV / XLSX / PDF（首卡仅 CSV 真实生成，XLSX/PDF 登记 backlog）。 */
    @Column(name = "format", nullable = false, length = 8)
    private String format;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "row_count")
    private Integer rowCount;

    /** 字节数；view 层格式化为 '248 KB' 串（照 mock 展示形态）。 */
    @Column(name = "file_size")
    private Integer fileSize;

    @Column(name = "error", length = 500)
    private String error;

    /** CSV 真实内容（含 BOM）；历史种子行为 NULL。 */
    @Column(name = "content")
    private byte[] content;

    /** SHA-256(content 原始字节，含 BOM) 64 位小写 hex（B56 验真指纹）；历史/失败行为 NULL。 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 冻结下载文件名（锚生成时刻 createdAt，多次下载同名）；B56 前任务为 NULL 走下载回退拼名。 */
    @Column(name = "file_name", length = 128)
    private String fileName;
}
