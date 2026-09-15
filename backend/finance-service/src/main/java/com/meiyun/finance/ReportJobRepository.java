package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;

public interface ReportJobRepository extends JpaRepository<ReportJob, String> {

    /** 列表查询走闭投影：只取标量列，避免前端轮询把 content（BYTEA）全量拉出。 */
    List<ReportJobSummary> findAllProjectedByOrderByCreatedAtDesc();

    /** 取号用：只取 id 列（'J%02d' max+1），不拉实体。 */
    @Query("select j.id from ReportJob j")
    List<String> findAllIds();

    /** 任务列表投影（不含 content）。 */
    interface ReportJobSummary {
        String getId();
        String getTemplateId();
        String getTemplateName();
        String getCategory();
        String getPeriod();
        String getStatus();
        String getFormat();
        OffsetDateTime getCreatedAt();
        String getCreatedBy();
        Integer getRowCount();
        Integer getFileSize();
        String getError();
    }
}
