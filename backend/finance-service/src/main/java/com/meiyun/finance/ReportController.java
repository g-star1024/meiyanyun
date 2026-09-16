package com.meiyun.finance;

import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * B49 卡11 报表中心 HTTP 端点（M1 集团屏 /m1-report）。
 *
 * <p>类级 {@code report:view} 兜底只读（模板/历史/订阅/预览）；生成/重试/下载方法级
 * {@code report:export}。权限矩阵已备（report:view/export 多角色），本卡不动矩阵。
 * 全部薄调 {@link ReportService}；CSV 附件响应照 FinanceController.csvResponse 先例
 * （Content-Disposition 中文文件名走 filename*=UTF-8'' 编码）。
 */
@RestController
@RequestMapping("/api/finance/report")
@RequirePerm("report:view")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** 模板列表。 */
    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return reportService.templates();
    }

    /** 生成历史（闭投影，不含 content，供前端 1s 轮询）。 */
    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        return reportService.jobs();
    }

    /** 订阅/退订（body.subscribed 缺省则取反）。 */
    @PostMapping("/templates/{id}/subscribe")
    public Map<String, Object> subscribe(@PathVariable("id") String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        return reportService.subscribe(id, body);
    }

    /** 数据预览（period 缺省：日报→昨天、月报→上月；截 50 行）。 */
    @GetMapping("/templates/{id}/preview")
    public Map<String, Object> preview(@PathVariable("id") String id,
                                       @RequestParam(required = false) String period) {
        return reportService.preview(id, period);
    }

    /** 触发生成（异步；report:export）。 */
    @PostMapping("/generate")
    @RequirePerm("report:export")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        return reportService.generate(body);
    }

    /** 失败任务重试（report:export）。 */
    @PostMapping("/jobs/{id}/retry")
    @RequirePerm("report:export")
    public Map<String, Object> retry(@PathVariable("id") String id) {
        return reportService.retry(id);
    }

    /** 哈希验真（B56）：重算当前 content 的 SHA-256 与生成时指纹比对（只读，吃类级 report:view，不新增权限码）。 */
    @GetMapping("/jobs/{id}/verify")
    public Map<String, Object> verify(@PathVariable("id") String id) {
        return reportService.verify(id);
    }

    /** 下载真实 CSV（report:export；历史种子行 content=NULL 走 404 提示路径）。 */
    @GetMapping("/jobs/{id}/download")
    @RequirePerm("report:export")
    public ResponseEntity<byte[]> download(@PathVariable("id") String id) {
        ReportService.CsvDownload dl = reportService.download(id);
        String encoded = URLEncoder.encode(dl.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"export.csv\"; filename*=UTF-8''" + encoded)
                .contentLength(dl.content().length)
                .body(dl.content());
    }
}
