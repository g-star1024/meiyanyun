package com.meiyun.finance;

import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Component
public class ReportAsyncRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportAsyncRunner.class);

    private final ReportJobRepository jobRepo;
    private final ReportTemplateRepository tplRepo;
    private final ReportBuilderRegistry registry;

    public ReportAsyncRunner(ReportJobRepository jobRepo, ReportTemplateRepository tplRepo,
                             ReportBuilderRegistry registry) {
        this.jobRepo = jobRepo;
        this.tplRepo = tplRepo;
        this.registry = registry;
    }

    @Async
    @Transactional
    public void run(String jobId, LoginUser operator) {
        SecurityContext.set(operator);
        try {
            ReportJob job = jobRepo.findById(jobId).orElse(null);
            if (job == null) {
                log.error("报表任务不存在 jobId={}", jobId);
                return;
            }
            try {
                ReportBuilder builder = registry.forFormat(job.getFormat());
                ReportBuildResult result = builder.build(job.getTemplateId(), job.getPeriod());
                job.setStatus("READY");
                job.setContent(result.content());
                job.setRowCount(result.rowCount());
                job.setFileSize(result.content().length);
                job.setError(null);
                job.setContentHash(ReportCsvBuilder.sha256Hex(result.content()));
                job.setFileName(ReportCsvBuilder.downloadFileName(
                        job.getTemplateName(), job.getPeriod(), job.getCreatedAt(), job.getFormat()));
                jobRepo.save(job);
                tplRepo.findById(job.getTemplateId()).ifPresent(t -> {
                    t.setLastRunAt(OffsetDateTime.now());
                    tplRepo.save(t);
                });
                log.info("报表生成完成 jobId={} template={} format={} period={} rows={}",
                        jobId, job.getTemplateId(), job.getFormat(), job.getPeriod(), result.rowCount());
            } catch (Exception e) {
                log.error("报表生成失败 jobId={} : {}", jobId, e.getMessage(), e);
                job.setStatus("FAILED");
                job.setError("生成失败，请稍后重试");
                job.setContent(null);
                job.setRowCount(null);
                job.setFileSize(null);
                job.setContentHash(null);
                job.setFileName(null);
                jobRepo.save(job);
            }
        } finally {
            SecurityContext.clear();
        }
    }
}
