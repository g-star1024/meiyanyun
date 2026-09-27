package com.meiyun.customer;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.Conflict;
import com.meiyun.customer.CustomerService.NotFound;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IoTaskService {

  private static final int MAX_ROWS = 5000;
  private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
  private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
  private static final Set<String> GENDERS = Set.of("男", "女");
  private static final Set<String> LEVELS = Set.of("普通", "银卡", "金卡", "钻石", "黑卡");
  private static final List<String> LEVEL_VIP = List.of("金卡", "钻石", "黑卡");
  private static final String DEFAULT_SEGMENT_NO = "SG0001";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final IoTaskRepository ioTaskRepository;
  private final CustomerRepository customerRepository;
  private final SegmentDefRepository segmentDefRepository;
  private final SegmentService segmentService;
  private final M3SettingsService m3SettingsService;
  private final AuditRecorder audit;

  public IoTaskService(
      IoTaskRepository ioTaskRepository,
      CustomerRepository customerRepository,
      SegmentDefRepository segmentDefRepository,
      SegmentService segmentService,
      M3SettingsService m3SettingsService,
      AuditRecorder audit) {
    this.ioTaskRepository = ioTaskRepository;
    this.customerRepository = customerRepository;
    this.segmentDefRepository = segmentDefRepository;
    this.segmentService = segmentService;
    this.m3SettingsService = m3SettingsService;
    this.audit = audit;
  }

  public record IoImportView(
      Long id,
      String taskNo,
      String fileName,
      Integer total,
      Integer success,
      Integer failed,
      String status,
      String operator,
      String createdAt,
      List<String> errors) {}

  public record IoExportView(
      Long id,
      String taskNo,
      String filter,
      Integer count,
      Boolean maskPhone,
      Boolean maskId,
      String operator,
      String createdAt) {}

  public record IoStats(
      Long monthImportTotal, Long monthExportTotal, Long pending, Double importSuccessRate) {}

  public record ExportCmd(String scope, String segmentNo, Boolean maskId) {}

  public record ExportResult(String fileName, byte[] bytes, IoExportView view) {}

  private record RowData(int rowNo, Map<Integer, String> cells) {}

  @Transactional(readOnly = true)
  public List<IoImportView> listImports() {
    return ioTaskRepository.findAllByTypeOrderByCreatedAtDesc(IoTask.TYPE_IMPORT).stream()
        .map(this::toImportView)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<IoExportView> listExports() {
    return ioTaskRepository.findAllByTypeOrderByCreatedAtDesc(IoTask.TYPE_EXPORT).stream()
        .map(t -> toExportView(t, scopeLabel(t.getScope())))
        .toList();
  }

  @Transactional(readOnly = true)
  public IoStats stats() {
    OffsetDateTime monthStart =
        OffsetDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    long monthImport = ioTaskRepository.sumTotalSince(IoTask.TYPE_IMPORT, monthStart);
    long monthExport = ioTaskRepository.sumTotalSince(IoTask.TYPE_EXPORT, monthStart);
    long pending = ioTaskRepository.countPendingImports();
    List<IoTask> imports = ioTaskRepository.findAllByTypeOrderByCreatedAtDesc(IoTask.TYPE_IMPORT);
    double rate = 0;
    if (!imports.isEmpty()) {
      long succ = imports.stream().filter(t -> IoTask.STATUS_DONE.equals(t.getStatus())).count();
      rate = Math.round(succ * 1000.0 / imports.size()) / 10.0;
    }
    return new IoStats(monthImport, monthExport, pending, rate);
  }

  @Transactional
  public IoImportView importFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BadReq("请上传要导入的文件");
    }
    String fileName = file.getOriginalFilename() == null ? "import" : file.getOriginalFilename();
    String lower = fileName.toLowerCase();
    boolean isCsv = lower.endsWith(".csv");
    boolean isXlsx = lower.endsWith(".xlsx");
    if (!isCsv && !isXlsx) {
      throw new BadReq("仅支持 .csv 或 .xlsx 文件");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new BadReq("文件读取失败，请重试");
    }
    if (bytes.length > MAX_FILE_BYTES) {
      throw new BadReq("文件大小不能超过 10MB");
    }
    String hash = sha256Hex(bytes);
    ioTaskRepository
        .findByFileHash(hash)
        .ifPresent(
            t -> {
              throw new Conflict("该文件已导入过，原任务单号：" + t.getTaskNo());
            });
    List<RowData> rows = isCsv ? parseCsv(bytes) : parseXlsx(bytes);
    if (rows.isEmpty()) {
      throw new BadReq("文件中没有可导入的数据行");
    }
    if (rows.size() > MAX_ROWS) {
      throw new BadReq("单次导入最多 " + MAX_ROWS + " 条");
    }

    List<Map<String, Object>> rowErrors = new ArrayList<>();
    int success = 0;
    for (RowData r : rows) {
      String name = cell(r.cells(), 0);
      String phone = cell(r.cells(), 1);
      String gender = cell(r.cells(), 2);
      String level = cell(r.cells(), 3);
      List<String> reasons = new ArrayList<>();
      if (name.isEmpty()) {
        reasons.add("姓名不能为空");
      }
      if (!PHONE.matcher(phone).matches()) {
        reasons.add("手机号格式不正确");
      }
      if (!gender.isEmpty() && !GENDERS.contains(gender)) {
        reasons.add("性别仅支持 男/女");
      }
      if (!level.isEmpty() && !LEVELS.contains(level)) {
        reasons.add("等级仅支持 普通/银卡/金卡/钻石/黑卡");
      }
      if (reasons.isEmpty()) {
        success++;
      } else {
        rowErrors.add(
            Map.of(
                "row", r.rowNo(),
                "name", name,
                "phone", phone,
                "reason", String.join("；", reasons)));
      }
    }
    int total = rows.size();
    int fail = total - success;

    String actor = DataScope.currentActor();
    IoTask task = new IoTask();
    task.setTaskNo(nextTaskNo());
    task.setType(IoTask.TYPE_IMPORT);
    task.setStatus(fail == 0 ? IoTask.STATUS_DONE : IoTask.STATUS_FAILED);
    task.setFileName(fileName);
    task.setFileHash(hash);
    task.setTotalCount(total);
    task.setSuccessCount(success);
    task.setFailCount(fail);
    task.setErrors(toJson(rowErrors));
    task.setStoreCode(ownStoreOrNull());
    task.setCreatedBy(actor);
    IoTask saved = ioTaskRepository.save(task);
    audit.record(
        "IO_TASK",
        saved.getTaskNo(),
        actor,
        "IMPORT",
        "{\"taskNo\":\""
            + saved.getTaskNo()
            + "\",\"fileName\":\""
            + escapeJson(fileName)
            + "\",\"total\":"
            + total
            + ",\"success\":"
            + success
            + ",\"fail\":"
            + fail
            + ",\"realActor\":\""
            + DataScope.currentRealActor()
            + "\"}");
    return toImportView(saved);
  }

  @Transactional
  public ExportResult export(ExportCmd cmd) {
    String scope =
        cmd == null || cmd.scope() == null || cmd.scope().isBlank()
            ? "ALL"
            : cmd.scope().trim().toUpperCase();
    Specification<Customer> spec =
        Specification.where(DataScope.<Customer>storeSpec("storeCode"))
            .and((root, q, cb) -> cb.isNull(root.get("mergedInto")));
    String filter;
    switch (scope) {
      case "ALL" -> filter = scopeLabel("ALL");
      case "TAG" -> {
        spec = spec.and((root, q, cb) -> cb.equal(root.get("intentLevel"), "高"));
        filter = scopeLabel("TAG");
      }
      case "LEVEL" -> {
        spec = spec.and((root, q, cb) -> root.get("level").in(LEVEL_VIP));
        filter = scopeLabel("LEVEL");
      }
      case "SEGMENT" -> {
        String segmentNo =
            cmd.segmentNo() == null || cmd.segmentNo().isBlank()
                ? DEFAULT_SEGMENT_NO
                : cmd.segmentNo().trim();
        SegmentDef def =
            segmentDefRepository
                .findBySegmentNo(segmentNo)
                .orElseThrow(() -> new NotFound("分群不存在：" + segmentNo));
        List<SegmentService.MemberView> members = segmentService.members(def.getId());
        Set<String> ids = new LinkedHashSet<>();
        for (SegmentService.MemberView m : members) {
          ids.add(m.id());
        }
        if (ids.isEmpty()) {
          spec = spec.and((root, q, cb) -> cb.disjunction());
        } else {
          spec = spec.and((root, q, cb) -> root.get("customerId").in(ids));
        }
        filter = scopeLabel("SEGMENT");
      }
      default -> throw new BadReq("不支持的导出范围：" + scope);
    }
    List<Customer> customers = customerRepository.findAll(spec);
    boolean maskPhone = readMaskPhoneSetting();
    boolean maskId = cmd == null || cmd.maskId() == null || cmd.maskId();
    String fileName =
        "客户导出-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".xlsx";
    byte[] bytes = buildXlsx(customers, maskPhone);

    String actor = DataScope.currentActor();
    IoTask task = new IoTask();
    task.setTaskNo(nextTaskNo());
    task.setType(IoTask.TYPE_EXPORT);
    task.setStatus(IoTask.STATUS_DONE);
    task.setScope(scope);
    task.setFileName(fileName);
    task.setTotalCount(customers.size());
    task.setSuccessCount(customers.size());
    task.setFailCount(0);
    task.setMaskPhone(maskPhone);
    task.setMaskIdCard(maskId);
    task.setStoreCode(ownStoreOrNull());
    task.setCreatedBy(actor);
    IoTask saved = ioTaskRepository.save(task);
    audit.record(
        "IO_TASK",
        saved.getTaskNo(),
        actor,
        "EXPORT",
        "{\"taskNo\":\""
            + saved.getTaskNo()
            + "\",\"scope\":\""
            + scope
            + "\",\"count\":"
            + customers.size()
            + ",\"maskPhone\":"
            + maskPhone
            + ",\"realActor\":\""
            + DataScope.currentRealActor()
            + "\"}");
    return new ExportResult(fileName, bytes, toExportView(saved, filter));
  }

  private byte[] buildXlsx(List<Customer> customers, boolean maskPhone) {
    List<List<String>> head =
        List.of(
            List.of("客户编号"), List.of("姓名"), List.of("手机号"), List.of("性别"), List.of("等级"),
            List.of("门店"), List.of("渠道"), List.of("累计消费"), List.of("到店次数"), List.of("状态"),
            List.of("建档时间"));
    List<List<Object>> data = new ArrayList<>();
    for (Customer c : customers) {
      List<Object> row = new ArrayList<>();
      row.add(str(c.getCustomerId()));
      row.add(str(c.getName()));
      row.add(maskPhone ? maskPhone(str(c.getPhone())) : str(c.getPhone()));
      row.add(str(c.getGender()));
      row.add(str(c.getLevel()));
      row.add(str(c.getStoreCode()));
      row.add(str(c.getChannel()));
      row.add(str(c.getTotalSpend()));
      row.add(str(c.getVisitCount()));
      row.add(str(c.getStatus()));
      row.add(fmtTime(c.getCreatedAt()));
      data.add(row);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    EasyExcel.write(out).head(head).sheet("客户导出").doWrite(data);
    return out.toByteArray();
  }

  private List<RowData> parseCsv(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    String[] lines = text.split("\r?\n");
    List<RowData> rows = new ArrayList<>();
    boolean headerSeen = false;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      if (!headerSeen) {
        headerSeen = true;
        continue;
      }
      String[] cells = line.split(",", -1);
      Map<Integer, String> m = new HashMap<>();
      for (int c = 0; c < cells.length; c++) {
        m.put(c, cells[c].trim());
      }
      rows.add(new RowData(i + 1, m));
    }
    return rows;
  }

  private List<RowData> parseXlsx(byte[] bytes) {
    List<Map<Integer, String>> raw;
    try {
      raw = EasyExcel.read(new ByteArrayInputStream(bytes)).sheet().doReadSync();
    } catch (Exception e) {
      throw new BadReq("Excel 解析失败，请使用下载的模板填写后重试");
    }
    List<RowData> rows = new ArrayList<>();
    for (int i = 1; i < raw.size(); i++) {
      Map<Integer, String> m = raw.get(i);
      boolean blank = m == null || m.values().stream().allMatch(v -> v == null || v.isBlank());
      if (blank) {
        continue;
      }
      rows.add(new RowData(i + 1, m));
    }
    return rows;
  }

  private synchronized String nextTaskNo() {
    String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String prefix = "IOT" + day + "-";
    int seq = 1;
    var max = ioTaskRepository.findTopByTaskNoLikeOrderByTaskNoDesc(prefix + "%");
    if (max.isPresent()) {
      String tail = max.get().getTaskNo().substring(prefix.length());
      seq = Integer.parseInt(tail) + 1;
    }
    return prefix + String.format("%06d", seq);
  }

  private boolean readMaskPhoneSetting() {
    try {
      Object v = m3SettingsService.loadMerged().get("maskPhoneInExport");
      return v instanceof Boolean b ? b : true;
    } catch (Exception e) {
      return true;
    }
  }

  private static String ownStoreOrNull() {
    LoginUser u = DataScope.current();
    return u == null ? null : u.storeCode();
  }

  private IoImportView toImportView(IoTask t) {
    return new IoImportView(
        t.getId(),
        t.getTaskNo(),
        t.getFileName(),
        t.getTotalCount(),
        t.getSuccessCount(),
        t.getFailCount(),
        t.getStatus(),
        t.getCreatedBy(),
        fmtTime(t.getCreatedAt()),
        errorsForView(t.getErrors()));
  }

  private IoExportView toExportView(IoTask t, String filter) {
    return new IoExportView(
        t.getId(),
        t.getTaskNo(),
        filter,
        t.getTotalCount(),
        t.getMaskPhone(),
        t.getMaskIdCard(),
        t.getCreatedBy(),
        fmtTime(t.getCreatedAt()));
  }

  private List<String> errorsForView(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<Map<String, Object>> errs = JSON.readValue(json, new TypeReference<>() {});
      List<String> out = new ArrayList<>();
      for (Map<String, Object> m : errs) {
        out.add("第" + m.get("row") + "行：" + m.get("reason"));
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private static String scopeLabel(String scope) {
    return switch (scope == null ? "ALL" : scope) {
      case "TAG" -> "意向标签：高意向";
      case "LEVEL" -> "等级：金卡及以上";
      case "SEGMENT" -> "分群：高意向高消费";
      default -> "全部客户";
    };
  }

  private static String cell(Map<Integer, String> cells, int idx) {
    String v = cells.get(idx);
    return v == null ? "" : v.trim();
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }

  private static String fmtTime(Object t) {
    if (t == null) {
      return "";
    }
    String s = t.toString();
    return s.length() >= 16 ? s.substring(0, 16).replace('T', ' ') : s;
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.isEmpty()) {
      return "";
    }
    if (phone.length() == 11) {
      return phone.substring(0, 3) + "****" + phone.substring(7);
    }
    if (phone.length() >= 8) {
      return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    return "****";
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(bytes);
      StringBuilder sb = new StringBuilder(d.length * 2);
      for (byte b : d) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String toJson(Object o) {
    try {
      return JSON.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
