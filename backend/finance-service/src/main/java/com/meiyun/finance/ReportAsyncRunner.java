package com.meiyun.finance;

import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 报表异步生成执行器（B49 卡11）。
 *
 * <p><b>数据域越权防线</b>：@Async 线程无请求 ThreadLocal，而 DataScope 对「无登录上下文」
 * 按服务间匿名调用语义<b>开放不过滤</b>（canReadStore u==null→true）——若不复原上下文，
 * 店长（STORE 域，持 report:export）生成的报表会拿到集团全量数据=越权。
 * 故入参显式携带操作人 {@link LoginUser}，run 内 set/finally clear 复原数据域上下文，
 * 生成口径=操作人数据域（与同步 preview 路径一致）。
 *
 * <p>@Async + @Transactional 同方法：由 ReportService 经代理外部调用生效；
 * 失败落 FAILED + 固定中文错误文案（技术详情记 log 不外泄）。
 */
@Component
public class ReportAsyncRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportAsyncRunner.class);

    private final ReportJobRepository jobRepo;
    private final ReportTemplateRepository tplRepo;
    private final ReportCsvBuilder csvBuilder;

    public ReportAsyncRunner(ReportJobRepository jobRepo, ReportTemplateRepository tplRepo,
                             ReportCsvBuilder csvBuilder) {
        this.jobRepo = jobRepo;
        this.tplRepo = tplRepo;
        this.csvBuilder = csvBuilder;
    }

    /** 异步执行生成：成功 READY+content+rowCount+fileSize 并回写模板 lastRunAt；失败 FAILED+error。 */
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
                ReportCsvBuilder.CsvData data = csvBuilder.build(job.getTemplateId(), job.getPeriod());
                job.setStatus("READY");
                job.setContent(data.content());
                job.setRowCount(data.rows().size());
                job.setFileSize(data.content().length);
                job.setError(null);
                // B56：指纹与冻结文件名在生成落库时一次性定型（哈希输入=含 BOM 最终字节本身）
                job.setContentHash(ReportCsvBuilder.sha256Hex(data.content()));
                job.setFileName(ReportCsvBuilder.downloadFileName(
                        job.getTemplateName(), job.getPeriod(), job.getCreatedAt()));
                jobRepo.save(job);
                tplRepo.findById(job.getTemplateId()).ifPresent(t -> {
                    t.setLastRunAt(OffsetDateTime.now());
                    tplRepo.save(t);
                });
                log.info("报表生成完成 jobId={} template={} period={} rows={}",
                        jobId, job.getTemplateId(), job.getPeriod(), data.rows().size());
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
