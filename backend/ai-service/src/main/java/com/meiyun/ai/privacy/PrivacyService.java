package com.meiyun.ai.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiPrivacyComplianceItem;
import com.meiyun.ai.domain.AiPrivacyComplianceItemRepository;
import com.meiyun.ai.domain.AiPrivacyExport;
import com.meiyun.ai.domain.AiPrivacyExportRepository;
import com.meiyun.ai.domain.AiPrivacyMaskRule;
import com.meiyun.ai.domain.AiPrivacyMaskRuleRepository;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A1 隐私合规（/ai/privacy）：脱敏规则启停、等保三级达标台账、合规报告导出。
 *
 * 诚实口径：
 * 1. 脱敏规则/等保目录为系统治理基线（V29 固定码 PM-/PC-SEED 幂等播种），
 *    「脱敏字段」KPI = 规则总数（不写死 48），「待处理」= 停用规则数 + 未达标项数，
 *    「审计记录」= audit_log 中 AI_PRIVACY 动作数 + 本页导出流水数（不写死 12840）。
 * 2. 导出哈希为真实哈希：范围头 + 区间内 audit_log 全链（id/prev_hash/cur_hash/payload）
 *    规范化拼接后 SHA-256；空区间只哈希范围头，audit_count=0 诚实标注，不伪造哈希。
 * 3. 翻转/导出全部写 AI_PRIVACY 审计；payload 为合法 JSON。
 */
@Service
public class PrivacyService {

    private static final ZoneOffset BJ = ZoneOffset.ofHours(8);
    private static final int PAGE_MAX = 200;
    private static final int RANGE_SPAN_MAX_DAYS = 366;
    private static final String BIZ_TYPE = "AI_PRIVACY";

    private final AiPrivacyMaskRuleRepository maskRuleRepo;
    private final AiPrivacyComplianceItemRepository complianceRepo;
    private final AiPrivacyExportRepository exportRepo;
    private final JdbcTemplate jdbcTemplate;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public PrivacyService(AiPrivacyMaskRuleRepository maskRuleRepo,
                          AiPrivacyComplianceItemRepository complianceRepo,
                          AiPrivacyExportRepository exportRepo,
                          JdbcTemplate jdbcTemplate,
                          AuditRecorder audit) {
        this.maskRuleRepo = maskRuleRepo;
        this.complianceRepo = complianceRepo;
        this.exportRepo = exportRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.audit = audit;
    }

    public record MaskRuleView(Long ruleId, String ruleCode, String fieldLabel, String moduleName,
                               String maskType, Boolean enabled, String staffName,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ComplianceItemView(Long itemId, String itemCode, String label, Boolean checked,
                                     String staffName, OffsetDateTime createdAt,
                                     OffsetDateTime updatedAt) {
    }

    public record ExportView(Long exportId, LocalDate rangeFrom, LocalDate rangeTo, Long auditCount,
                             String reportHash, String staffName, OffsetDateTime createdAt) {
    }

    public record PrivacyStats(long maskFieldCount, long compliancePct, long pendingCount,
                               long auditCount) {
    }

    public record ExportCmd(LocalDate rangeFrom, LocalDate rangeTo) {
    }

    public PrivacyStats stats() {
        long maskTotal = maskRuleRepo.count();
        long disabled = maskRuleRepo.countByEnabledFalse();
        long itemTotal = complianceRepo.count();
        long checked = complianceRepo.countByCheckedTrue();
        long unchecked = itemTotal - checked;
        long pct = itemTotal == 0 ? 0 : Math.round(checked * 100.0 / itemTotal);
        Long privacyAuditCount = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where biz_type = ?", Long.class, BIZ_TYPE);
        long auditTotal = (privacyAuditCount == null ? 0L : privacyAuditCount) + exportRepo.count();
        return new PrivacyStats(maskTotal, pct, disabled + unchecked, auditTotal);
    }

    public List<MaskRuleView> maskRules() {
        return maskRuleRepo.findAllByOrderByRuleIdAsc().stream().map(this::toMaskView).toList();
    }

    public List<ComplianceItemView> complianceItems() {
        return complianceRepo.findAllByOrderByItemIdAsc().stream().map(this::toComplianceView).toList();
    }

    public Page<ExportView> exports(int page, int size) {
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), s);
        return exportRepo.findAllByOrderByExportIdDesc(pageable).map(this::toExportView);
    }

    // 刻意不加方法级事务：save 经仓储自身事务提交后 findById 全新读回触发器维护的 updated_at。
    public MaskRuleView toggleMaskRule(Long id, String actor) {
        LoginUser user = requireUser();
        AiPrivacyMaskRule rule = maskRuleRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "脱敏规则不存在（id=" + id + "）"));
        boolean from = Boolean.TRUE.equals(rule.getEnabled());
        boolean to = !from;
        rule.setEnabled(to);
        rule.setStaffId(user.staffId());
        rule.setStaffName(user.staffName());
        rule.setStoreCode(user.storeCode());
        maskRuleRepo.save(rule);
        audit.record(BIZ_TYPE, "PM-RULE-" + id, actor, "TOGGLE_MASK",
                payload(Map.of("ruleCode", nz(rule.getRuleCode()), "fieldLabel", rule.getFieldLabel(),
                        "maskType", rule.getMaskType(), "from", from, "to", to)));
        return maskRuleRepo.findById(id).map(this::toMaskView).orElseGet(() -> toMaskView(rule));
    }

    public ComplianceItemView toggleComplianceItem(Long id, String actor) {
        LoginUser user = requireUser();
        AiPrivacyComplianceItem item = complianceRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "等保达标项不存在（id=" + id + "）"));
        boolean from = Boolean.TRUE.equals(item.getChecked());
        boolean to = !from;
        item.setChecked(to);
        item.setStaffId(user.staffId());
        item.setStaffName(user.staffName());
        item.setStoreCode(user.storeCode());
        complianceRepo.save(item);
        audit.record(BIZ_TYPE, "PC-ITEM-" + id, actor, "TOGGLE_COMPLIANCE",
                payload(Map.of("itemCode", nz(item.getItemCode()), "label", item.getLabel(),
                        "from", from, "to", to)));
        return complianceRepo.findById(id).map(this::toComplianceView)
                .orElseGet(() -> toComplianceView(item));
    }

    public ExportView createExport(ExportCmd cmd, String actor) {
        LoginUser user = requireUser();
        LocalDate from = requireDate(cmd == null ? null : cmd.rangeFrom(), "导出开始日期");
        LocalDate to = requireDate(cmd == null ? null : cmd.rangeTo(), "导出结束日期");
        if (to.isBefore(from)) {
            throw badRequest("导出结束日期不能早于开始日期");
        }
        if (from.plusDays(RANGE_SPAN_MAX_DAYS).isBefore(to)) {
            throw badRequest("导出区间跨度不能超过 " + RANGE_SPAN_MAX_DAYS + " 天");
        }

        OffsetDateTime fromInstant = from.atStartOfDay().atOffset(BJ);
        OffsetDateTime toInstant = to.plusDays(1).atStartOfDay().atOffset(BJ);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, prev_hash, cur_hash, payload from audit_log "
                        + "where created_at >= ? and created_at < ? order by id asc",
                java.sql.Timestamp.from(fromInstant.toInstant()),
                java.sql.Timestamp.from(toInstant.toInstant()));

        StringBuilder canonical = new StringBuilder("HEADER|AI_PRIVACY|")
                .append(from).append('|').append(to).append('|').append(rows.size()).append('\n');
        for (Map<String, Object> row : rows) {
            canonical.append(row.get("id")).append('|')
                    .append(nz(String.valueOf(row.get("prev_hash")))).append('|')
                    .append(nz(String.valueOf(row.get("cur_hash")))).append('|')
                    .append(normalizePayload(row.get("payload"))).append('\n');
        }
        String hash = sha256Hex(canonical.toString());

        AiPrivacyExport export = new AiPrivacyExport();
        export.setRangeFrom(from);
        export.setRangeTo(to);
        export.setAuditCount((long) rows.size());
        export.setReportHash(hash);
        export.setStaffId(user.staffId());
        export.setStaffName(user.staffName());
        export.setStoreCode(user.storeCode());
        AiPrivacyExport saved = exportRepo.save(export);
        audit.record(BIZ_TYPE, "PM-EXPORT-" + saved.getExportId(), actor, "EXPORT_REPORT",
                payload(Map.of("rangeFrom", from.toString(), "rangeTo", to.toString(),
                        "auditCount", rows.size(), "reportHash", hash)));
        return exportRepo.findById(saved.getExportId()).map(this::toExportView)
                .orElseGet(() -> toExportView(saved));
    }

    private MaskRuleView toMaskView(AiPrivacyMaskRule r) {
        return new MaskRuleView(r.getRuleId(), r.getRuleCode(), r.getFieldLabel(), r.getModuleName(),
                r.getMaskType(), r.getEnabled(), r.getStaffName(), r.getCreatedAt(), r.getUpdatedAt());
    }

    private ComplianceItemView toComplianceView(AiPrivacyComplianceItem i) {
        return new ComplianceItemView(i.getItemId(), i.getItemCode(), i.getLabel(), i.getChecked(),
                i.getStaffName(), i.getCreatedAt(), i.getUpdatedAt());
    }

    private ExportView toExportView(AiPrivacyExport e) {
        return new ExportView(e.getExportId(), e.getRangeFrom(), e.getRangeTo(), e.getAuditCount(),
                e.getReportHash(), e.getStaffName(), e.getCreatedAt());
    }

    private String normalizePayload(Object payload) {
        if (payload == null) {
            return "";
        }
        String raw = payload.toString();
        try {
            return json.writeValueAsString(json.readTree(raw));
        } catch (Exception e) {
            return raw;
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private LocalDate requireDate(LocalDate date, String field) {
        if (date == null) {
            throw badRequest(field + "不能为空（格式 yyyy-MM-dd）");
        }
        return date;
    }

    private LoginUser requireUser() {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        return user;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
