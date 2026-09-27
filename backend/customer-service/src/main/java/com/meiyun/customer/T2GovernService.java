package com.meiyun.customer;

import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.Conflict;
import com.meiyun.customer.CustomerService.NotFound;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2-B1 数据治理服务（DESIGN-T2，/api/customer/t2/govern）。
 * 质量规则 CRUD＋启停（直改直存无状态机）；问题单状态机 OPEN→[RESOLVED,IGNORED] 终态空
 * （违反→409 中文透出当前态，幂等提交方据此识别已流转）；
 * resolve 同事务回填来源规则 error_count=max(0,-count)（与前端 mock resolveIssue 语义一致，
 * 规则缺失时软跳过对齐 mock if(r) 语义）；ignore 不回填 resolved_at（对齐 mock）。
 * 血缘 nodes+edges 只读透传。操作人取 DataScope.currentActor()（请求体不收 actor，防伪造）。
 */
@Service
public class T2GovernService {

    /** 问题单状态机迁移表（与前端 stores/t2DataGovern.ts resolve/ignore 前置 OPEN 语义一致）。 */
    private static final Map<String, Set<String>> ISSUE_TRANSITIONS = Map.of(
            DataGovernIssue.STATUS_OPEN, Set.of(DataGovernIssue.STATUS_RESOLVED, DataGovernIssue.STATUS_IGNORED),
            DataGovernIssue.STATUS_RESOLVED, Set.of(),
            DataGovernIssue.STATUS_IGNORED, Set.of());

    private static final Set<String> RULE_TYPES = Set.of(
            DataGovernRule.TYPE_NOT_NULL, DataGovernRule.TYPE_UNIQUE, DataGovernRule.TYPE_RANGE,
            DataGovernRule.TYPE_REGEX, DataGovernRule.TYPE_CUSTOM);
    private static final Set<String> SEVERITIES = Set.of(
            DataGovernRule.SEVERITY_HIGH, DataGovernRule.SEVERITY_MEDIUM, DataGovernRule.SEVERITY_LOW);

    private final DataGovernRuleRepository ruleRepository;
    private final DataGovernIssueRepository issueRepository;
    private final DataLineageNodeRepository nodeRepository;
    private final DataLineageEdgeRepository edgeRepository;
    private final AuditRecorder audit;

    public T2GovernService(DataGovernRuleRepository ruleRepository, DataGovernIssueRepository issueRepository,
                           DataLineageNodeRepository nodeRepository, DataLineageEdgeRepository edgeRepository,
                           AuditRecorder audit) {
        this.ruleRepository = ruleRepository;
        this.issueRepository = issueRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.audit = audit;
    }

    /** 规则视图（字段名映射回前端契约：tableName→table / columnName→column / ruleType→type）。 */
    public record RuleView(Long id, String name, String table, String column, String type,
                           String severity, String expression, Boolean enabled, String lastCheckAt,
                           Double passRate, Integer errorCount, String owner, String createdAt) {}

    /** 问题单视图（errorCount 映射回契约字段 count；table/column 同规则视图映射）。 */
    public record IssueView(Long id, Long ruleId, String ruleName, String table, String column,
                            String sample, Integer count, String status,
                            String detectedAt, String resolvedAt) {}

    /** 血缘节点视图（nodeType 映射回契约字段 type）。 */
    public record LineageNodeView(String id, String name, String type, Integer x, Integer y) {}

    /** 血缘边视图（fromNode/toNode 映射回契约 from/to；契约无 id 字段故不透出）。 */
    public record LineageEdgeView(String from, String to) {}

    public record LineageView(List<LineageNodeView> nodes, List<LineageEdgeView> edges) {}

    /** 规则列表：id 升序（种子插入序=前端 mock 数组序，id 自增天然保序）。 */
    @Transactional(readOnly = true)
    public List<RuleView> listRules() {
        return ruleRepository.findAllByOrderByIdAsc().stream().map(T2GovernService::toRuleView).toList();
    }

    /** 新建规则：名称/表名必填＋词表校验；缺省 enabled=true/lastCheckAt=null/passRate=100/errorCount=0/owner=当前登录人姓名（对齐 mock auth.user.name，展示用；审计 actor 仍记工号）；落 CREATE 审计。 */
    @Transactional
    public RuleView createRule(String name, String table, String column, String type,
                               String severity, String expression, Boolean enabled) {
        validateRule(name, table, type, severity);
        String actor = DataScope.currentActor();
        LoginUser u = SecurityContext.get();
        String ownerName = (u != null && u.staffName() != null && !u.staffName().isBlank()) ? u.staffName() : actor;
        DataGovernRule r = new DataGovernRule();
        r.setName(name.trim());
        r.setTableName(table.trim());
        r.setColumnName(column == null ? "" : column.trim());
        r.setRuleType(type);
        r.setSeverity(severity);
        r.setExpression(expression == null ? "" : expression.trim());
        r.setEnabled(enabled == null || enabled);
        r.setLastCheckAt(null);
        r.setPassRate(new BigDecimal("100"));
        r.setErrorCount(0);
        r.setOwner(ownerName);
        DataGovernRule saved = ruleRepository.save(r);
        audit.record("GOVERN_RULE", "RULE-" + saved.getId(), actor, "CREATE",
                "{\"name\":\"" + esc(saved.getName()) + "\",\"type\":\"" + saved.getRuleType()
                        + "\",\"severity\":\"" + saved.getSeverity() + "\"}");
        return toRuleView(saved);
    }

    /** 编辑规则：patch 语义（与前端 Object.assign 一致），非空字段才覆盖；词表校验；落 UPDATE 审计。 */
    @Transactional
    public RuleView updateRule(Long id, String name, String table, String column, String type,
                               String severity, String expression, Boolean enabled) {
        DataGovernRule r = mustGetRule(id);
        if (name != null && !name.isBlank()) {
            r.setName(name.trim());
        }
        if (table != null && !table.isBlank()) {
            r.setTableName(table.trim());
        }
        if (column != null) {
            r.setColumnName(column.trim());
        }
        if (type != null) {
            if (!RULE_TYPES.contains(type)) {
                throw new BadReq("规则类型不合法，仅支持 NOT_NULL/UNIQUE/RANGE/REGEX/CUSTOM");
            }
            r.setRuleType(type);
        }
        if (severity != null) {
            if (!SEVERITIES.contains(severity)) {
                throw new BadReq("严重度不合法，仅支持 HIGH/MEDIUM/LOW");
            }
            r.setSeverity(severity);
        }
        if (expression != null) {
            r.setExpression(expression.trim());
        }
        if (enabled != null) {
            r.setEnabled(enabled);
        }
        String actor = DataScope.currentActor();
        DataGovernRule saved = ruleRepository.save(r);
        audit.record("GOVERN_RULE", "RULE-" + saved.getId(), actor, "UPDATE",
                "{\"name\":\"" + esc(saved.getName()) + "\"}");
        return toRuleView(saved);
    }

    /** 启停切换：enabled 翻转直存（不动 lastCheckAt，对齐 mock toggleRule）；落 TOGGLE 审计。 */
    @Transactional
    public RuleView toggleRule(Long id) {
        DataGovernRule r = mustGetRule(id);
        r.setEnabled(!Boolean.TRUE.equals(r.getEnabled()));
        String actor = DataScope.currentActor();
        DataGovernRule saved = ruleRepository.save(r);
        audit.record("GOVERN_RULE", "RULE-" + saved.getId(), actor, "TOGGLE",
                "{\"enabled\":" + saved.getEnabled() + "}");
        return toRuleView(saved);
    }

    /** 问题单列表：id 升序（种子插入序=前端 mock 数组序）。 */
    @Transactional(readOnly = true)
    public List<IssueView> listIssues() {
        return issueRepository.findAllByOrderByIdAsc().stream().map(T2GovernService::toIssueView).toList();
    }

    /** 标记已解决：OPEN→RESOLVED；记解决时刻；同事务回填来源规则 error_count=max(0,-count)（规则缺失软跳过）；落 RESOLVE 审计。 */
    @Transactional
    public IssueView resolveIssue(Long id) {
        DataGovernIssue i = mustGetIssue(id);
        mustTransit(i, DataGovernIssue.STATUS_RESOLVED);
        String actor = DataScope.currentActor();
        i.setStatus(DataGovernIssue.STATUS_RESOLVED);
        i.setResolvedAt(OffsetDateTime.now());
        DataGovernIssue saved = issueRepository.save(i);
        ruleRepository.findById(i.getRuleId()).ifPresent(r -> {
            r.setErrorCount(Math.max(0, (r.getErrorCount() == null ? 0 : r.getErrorCount())
                    - (i.getErrorCount() == null ? 0 : i.getErrorCount())));
            ruleRepository.save(r);
        });
        audit.record("GOVERN_ISSUE", "ISSUE-" + saved.getId(), actor, "RESOLVE",
                "{\"ruleName\":\"" + esc(saved.getRuleName()) + "\",\"count\":"
                        + (saved.getErrorCount() == null ? 0 : saved.getErrorCount()) + "}");
        return toIssueView(saved);
    }

    /** 忽略：OPEN→IGNORED；不回填 resolved_at（对齐 mock ignoreIssue）；落 IGNORE 审计。 */
    @Transactional
    public IssueView ignoreIssue(Long id) {
        DataGovernIssue i = mustGetIssue(id);
        mustTransit(i, DataGovernIssue.STATUS_IGNORED);
        String actor = DataScope.currentActor();
        i.setStatus(DataGovernIssue.STATUS_IGNORED);
        DataGovernIssue saved = issueRepository.save(i);
        audit.record("GOVERN_ISSUE", "ISSUE-" + saved.getId(), actor, "IGNORE",
                "{\"ruleName\":\"" + esc(saved.getRuleName()) + "\"}");
        return toIssueView(saved);
    }

    /** 血缘：nodes 按画布坐标 (x,y) 升序（组内相对序与 mock 数组序一致），edges 按 id 升序（种子插入序）。 */
    @Transactional(readOnly = true)
    public LineageView lineage() {
        List<LineageNodeView> nodes = nodeRepository.findAll(Sort.by("x", "y")).stream()
                .map(n -> new LineageNodeView(n.getId(), n.getName(), n.getNodeType(), n.getX(), n.getY()))
                .toList();
        List<LineageEdgeView> edges = edgeRepository.findAllByOrderByIdAsc().stream()
                .map(e -> new LineageEdgeView(e.getFromNode(), e.getToNode()))
                .toList();
        return new LineageView(nodes, edges);
    }

    private void validateRule(String name, String table, String type, String severity) {
        if (name == null || name.isBlank()) {
            throw new BadReq("规则名称必填");
        }
        if (table == null || table.isBlank()) {
            throw new BadReq("目标表名必填");
        }
        if (type == null || !RULE_TYPES.contains(type)) {
            throw new BadReq("规则类型不合法，仅支持 NOT_NULL/UNIQUE/RANGE/REGEX/CUSTOM");
        }
        if (severity == null || !SEVERITIES.contains(severity)) {
            throw new BadReq("严重度不合法，仅支持 HIGH/MEDIUM/LOW");
        }
    }

    private DataGovernRule mustGetRule(Long id) {
        return ruleRepository.findById(id).orElseThrow(() -> new NotFound("质量规则不存在"));
    }

    private DataGovernIssue mustGetIssue(Long id) {
        return issueRepository.findById(id).orElseThrow(() -> new NotFound("数据问题单不存在"));
    }

    /** 状态机前置校验：违反→409 中文透出当前态（幂等提交方据此识别已流转）。 */
    private static void mustTransit(DataGovernIssue i, String to) {
        if (!ISSUE_TRANSITIONS.getOrDefault(i.getStatus(), Set.of()).contains(to)) {
            throw new Conflict("当前状态「" + statusLabel(i.getStatus()) + "」不允许此操作");
        }
    }

    private static RuleView toRuleView(DataGovernRule r) {
        return new RuleView(
                r.getId(),
                r.getName(),
                r.getTableName(),
                r.getColumnName(),
                r.getRuleType(),
                r.getSeverity(),
                r.getExpression(),
                Boolean.TRUE.equals(r.getEnabled()),
                r.getLastCheckAt() == null ? null : r.getLastCheckAt().toString(),
                r.getPassRate() == null ? 100.0 : r.getPassRate().doubleValue(),
                r.getErrorCount() == null ? 0 : r.getErrorCount(),
                r.getOwner(),
                r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
    }

    private static IssueView toIssueView(DataGovernIssue i) {
        return new IssueView(
                i.getId(),
                i.getRuleId(),
                i.getRuleName(),
                i.getTableName(),
                i.getColumnName(),
                i.getSample(),
                i.getErrorCount() == null ? 0 : i.getErrorCount(),
                i.getStatus(),
                i.getDetectedAt() == null ? "" : i.getDetectedAt().toString(),
                i.getResolvedAt() == null ? null : i.getResolvedAt().toString());
    }

    /** 状态中文标签（4xx 报错透出当前态，便于前端/运维直读）。 */
    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case DataGovernIssue.STATUS_OPEN -> "待处理";
            case DataGovernIssue.STATUS_RESOLVED -> "已解决";
            case DataGovernIssue.STATUS_IGNORED -> "已忽略";
            default -> status == null ? "未知" : status;
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
