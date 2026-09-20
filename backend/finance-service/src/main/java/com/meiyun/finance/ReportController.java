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

@RestController
@RequestMapping("/api/finance/report")
@RequirePerm("report:view")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/templates")
    public List<Map<String, Object>> templates() {
        return reportService.templates();
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        return reportService.jobs();
    }

    @PostMapping("/templates/{id}/subscribe")
    public Map<String, Object> subscribe(@PathVariable("id") String id,
                                         @RequestBody(required = false) Map<String, Object> body) {
        return reportService.subscribe(id, body);
    }

    @GetMapping("/templates/{id}/preview")
    public Map<String, Object> preview(@PathVariable("id") String id,
                                       @RequestParam(required = false) String period) {
        return reportService.preview(id, period);
    }

    @PostMapping("/generate")
    @RequirePerm("report:export")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        return reportService.generate(body);
    }

    @PostMapping("/jobs/{id}/retry")
    @RequirePerm("report:export")
    public Map<String, Object> retry(@PathVariable("id") String id) {
        return reportService.retry(id);
    }

    @GetMapping("/jobs/{id}/verify")
    public Map<String, Object> verify(@PathVariable("id") String id) {
        return reportService.verify(id);
    }

    @GetMapping("/jobs/{id}/download")
    @RequirePerm("report:export")
    public ResponseEntity<byte[]> download(@PathVariable("id") String id) {
        ReportService.FileDownload dl = reportService.download(id);
        String encoded = URLEncoder.encode(dl.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, dl.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentLength(dl.content().length)
                .body(dl.content());
    }
}
