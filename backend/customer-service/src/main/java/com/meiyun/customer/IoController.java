package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/customer/m3/io")
public class IoController {

  private final IoTaskService ioTaskService;

  public IoController(IoTaskService ioTaskService) {
    this.ioTaskService = ioTaskService;
  }

  @GetMapping("/imports")
  @RequirePerm({"io:import", "io:export"})
  public List<IoTaskService.IoImportView> imports() {
    return ioTaskService.listImports();
  }

  @GetMapping("/exports")
  @RequirePerm({"io:import", "io:export"})
  public List<IoTaskService.IoExportView> exports() {
    return ioTaskService.listExports();
  }

  @GetMapping("/stats")
  @RequirePerm({"io:import", "io:export"})
  public IoTaskService.IoStats stats() {
    return ioTaskService.stats();
  }

  @PostMapping("/import")
  @RequirePerm("io:import")
  public IoTaskService.IoImportView importFile(@RequestParam("file") MultipartFile file) {
    return ioTaskService.importFile(file);
  }

  @GetMapping("/import-template")
  @RequirePerm("io:import")
  public ResponseEntity<byte[]> importTemplate() {
    IoTaskService.TemplateFile tpl = ioTaskService.importTemplate();
    String encoded =
        URLEncoder.encode(tpl.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(tpl.bytes());
  }

  @PostMapping("/export")
  @RequirePerm("io:export")
  public ResponseEntity<byte[]> export(@RequestBody(required = false) IoTaskService.ExportCmd cmd) {
    IoTaskService.ExportResult r = ioTaskService.export(cmd);
    String encoded =
        URLEncoder.encode(r.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(r.bytes());
  }
}
