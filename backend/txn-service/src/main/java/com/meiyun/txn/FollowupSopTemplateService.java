package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 术后 SOP 模板编排 + 批次执行看板服务（P5-B31）：模板节点 CRUD/启停/恢复默认、
 * 批次分页聚合、KPI 汇总、超期一键升级。
 *
 * <p>铁律：读 followup:view / 写 followup:edit（Controller 注解）；批次与节点均按 JWT 门店数据域过滤
 * （显式他店码 404）；全部写动作落审计（模板 SOP_TEMPLATE / 升级 FOLLOWUP）；
 * 参数白名单 + 中文错误；内置四阶段可停用/改天数改方式但不可删除，仅 MANUAL 自定义节点可删。
 * 模板调整只影响此后新批次（历史随访节点只冗余 stage/label）。</p>
 */
@Service
public class FollowupSopTemplateService {

    private static final Set<String> METHODS = Set.of("PHONE", "WECHAT", "IN_STORE");
    private static final ZoneOffset BIZ_TZ = ZoneOffset.ofHours(8);
    private static final int MAX_BATCHES_FETCH = 200;

    private final FollowupSopTemplateRepository templateRepo;
    private final FollowupSopTemplateNodeRepository nodeRepo;
    private final FollowupSopBatchRepository batchRepo;
    private final FollowupRepository followupRepo;
    private final FollowupSopEscalator escalator;
    private final AuditRecorder audit;

    @PersistenceContext
    private EntityManager em;

    public FollowupSopTemplateService(FollowupSopTemplateRepository templateRepo,
                                      FollowupSopTemplateNodeRepository nodeRepo,
                                      FollowupSopBatchRepository batchRepo,
                                      FollowupRepository followupRepo,
                                      FollowupSopEscalator escalator,
                                      AuditRecorder audit) {
        this.templateRepo = templateRepo;
        this.nodeRepo = nodeRepo;
        this.batchRepo = batchRepo;
        this.followupRepo = followupRepo;
        this.escalator = escalator;
        this.audit = audit;
    }

    /** 批次聚合行：批次头 + 节点列表 + 完成/超期计数 + 是否完结。 */
    public record BatchAgg(FollowupSopBatch batch, List<Followup> nodes,
                           int total, int done, int overdue, boolean finished) {}

    public record NodeCmd(String label, Integer dayOffset, String method) {}

    // ==================== 模板编排 ====================

    /**
     * 取集团通用模板的全部节点（含停用，按行号升序）。
     * 未播种环境（如 prod 首次打开）幂等懒初始化模板 + 内置四节点（system 动作，不落业务审计）。
     */
    @Transactional
    public List<FollowupSopTemplateNode> getTemplate() {
        ensureDefaultTemplate();
        return nodeRepo.findByTemplateNoOrderByLineNoAsc(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
    }

    /** 新增自定义节点（stage=MANUAL）：名称必填、天数 0-365、方式白名单；插入后按天数重排行号。 */
    @Transactional
    public List<FollowupSopTemplateNode> addNode(NodeCmd cmd) {
        ensureDefaultTemplate();
        String label = requireLabel(cmd.label());
        int dayOffset = requireDayOffset(cmd.dayOffset());
        String method = requireMethod(cmd.method());
        String actor = DataScope.currentActor();

        FollowupSopTemplateNode n = new FollowupSopTemplateNode();
        n.setTemplateNo(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
        n.setLineNo(Integer.MAX_VALUE);
        n.setStage("MANUAL");
        n.setLabel(label);
        n.setDayOffset(dayOffset);
        n.setMethod(method);
        n.setEnabled(true);
        FollowupSopTemplateNode saved = nodeRepo.save(n);
        renumber();
        audit.record("SOP_TEMPLATE", FollowupSopDefaults.DEFAULT_TEMPLATE_NO, actor, "NODE_ADD",
                "{\"nodeId\":" + saved.getId()
                        + ",\"label\":\"" + esc(label) + "\",\"dayOffset\":" + dayOffset
                        + ",\"method\":\"" + method + "\"}");
        return nodeRepo.findByTemplateNoOrderByLineNoAsc(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
    }

    /** 改节点：名称/天数/方式（内置与自定义均可改）；不存在 404，参数白名单。 */
    @Transactional
    public List<FollowupSopTemplateNode> updateNode(Long nodeId, NodeCmd cmd) {
        FollowupSopTemplateNode n = requireNode(nodeId);
        String actor = DataScope.currentActor();
        StringBuilder payload = new StringBuilder("{\"nodeId\":" + nodeId);
        if (!blank(cmd.label())) {
            String label = requireLabel(cmd.label());
            n.setLabel(label);
            payload.append(",\"label\":\"").append(esc(label)).append('"');
        }
        if (cmd.dayOffset() != null) {
            int dayOffset = requireDayOffset(cmd.dayOffset());
            n.setDayOffset(dayOffset);
            payload.append(",\"dayOffset\":").append(dayOffset);
        }
        if (!blank(cmd.method())) {
            String method = requireMethod(cmd.method());
            n.setMethod(method);
            payload.append(",\"method\":\"").append(method).append('"');
        }
        nodeRepo.save(n);
        renumber();
        payload.append('}');
        audit.record("SOP_TEMPLATE", FollowupSopDefaults.DEFAULT_TEMPLATE_NO, actor, "NODE_UPDATE",
                payload.toString());
        return nodeRepo.findByTemplateNoOrderByLineNoAsc(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
    }

    /** 启停节点：停用后不参与新批次排程，历史批次不变。 */
    @Transactional
    public List<FollowupSopTemplateNode> toggleNode(Long nodeId, boolean enabled) {
        FollowupSopTemplateNode n = requireNode(nodeId);
        n.setEnabled(enabled);
        nodeRepo.save(n);
        audit.record("SOP_TEMPLATE", FollowupSopDefaults.DEFAULT_TEMPLATE_NO, DataScope.currentActor(),
                "NODE_TOGGLE", "{\"nodeId\":" + nodeId + ",\"enabled\":" + enabled + "}");
        return nodeRepo.findByTemplateNoOrderByLineNoAsc(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
    }

    /** 删除节点：仅 MANUAL 自定义节点可删；内置四阶段不可删（引导停用），中文 400。 */
    @Transactional
    public List<FollowupSopTemplateNode> deleteNode(Long nodeId) {
        FollowupSopTemplateNode n = requireNode(nodeId);
        if (!"MANUAL".equals(n.getStage())) {
            throw badRequest("内置节点不可删除，可停用该节点（停用后不再生成新批次）");
        }
        nodeRepo.delete(n);
        renumber();
        audit.record("SOP_TEMPLATE", FollowupSopDefaults.DEFAULT_TEMPLATE_NO, DataScope.currentActor(),
                "NODE_DELETE", "{\"nodeId\":" + nodeId + ",\"label\":\"" + esc(n.getLabel()) + "\"}");
        return nodeRepo.findByTemplateNoOrderByLineNoAsc(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
    }

    /**
     * 恢复默认：清空当前模板全部节点（含自定义），按 {@link FollowupSopDefaults} 重建内置四节点并全部启用。
     * 模板头保留（含模板号），不重建。
     */
    @Transactional
    public List<FollowupSopTemplateNode> resetTemplate() {
        ensureDefaultTemplate();
        String tplNo = FollowupSopDefaults.DEFAULT_TEMPLATE_NO;
        List<FollowupSopTemplateNode> existing = nodeRepo.findByTemplateNoOrderByLineNoAsc(tplNo);
        boolean sameAsDefault = existing.size() == FollowupSopDefaults.NODES.size();
        if (sameAsDefault) {
            for (int i = 0; i < existing.size(); i++) {
                FollowupSopDefaults.NodeDef d = FollowupSopDefaults.NODES.get(i);
                FollowupSopTemplateNode n = existing.get(i);
                if (!d.stage().equals(n.getStage()) || !d.label().equals(n.getLabel())
                        || d.dayOffset() != n.getDayOffset() || !d.method().equals(n.getMethod())
                        || !Boolean.TRUE.equals(n.getEnabled())) {
                    sameAsDefault = false;
                    break;
                }
            }
        }
        if (sameAsDefault) {
            return existing;
        }
        nodeRepo.deleteByTemplateNo(tplNo);
        nodeRepo.flush();
        int lineNo = 1;
        List<FollowupSopTemplateNode> rebuilt = new ArrayList<>();
        for (FollowupSopDefaults.NodeDef d : FollowupSopDefaults.NODES) {
            FollowupSopTemplateNode n = new FollowupSopTemplateNode();
            n.setTemplateNo(tplNo);
            n.setLineNo(lineNo++);
            n.setStage(d.stage());
            n.setLabel(d.label());
            n.setDayOffset(d.dayOffset());
            n.setMethod(d.method());
            n.setEnabled(true);
            rebuilt.add(n);
        }
        nodeRepo.saveAll(rebuilt);
        audit.record("SOP_TEMPLATE", tplNo, DataScope.currentActor(), "RESET",
                "{\"nodes\":" + FollowupSopDefaults.NODES.size() + "}");
        return nodeRepo.findByTemplateNoOrderByLineNoAsc(tplNo);
    }

    // ==================== 批次执行看板 ====================

    /**
     * 批次分页聚合：门店数据域过滤；keyword 模糊客户名/项目/批次号；
     * 排序「未完结在前、服务日期倒序、批次号倒序」（页内重排）；每批批量装配全部节点（避免 N+1）。
     */
    @Transactional(readOnly = true)
    public Page<BatchAgg> batches(String storeCode, String keyword, Pageable pageable) {
        Specification<FollowupSopBatch> spec = batchScope(storeCode);
        String kw = trimToNull(keyword);
        if (kw != null) {
            String like = "%" + kw.toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("customerName")), like),
                    cb.like(cb.lower(root.get("project")), like),
                    cb.like(cb.lower(root.get("batchNo")), like)));
        }
        PageRequest page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("serviceDate"), Sort.Order.desc("batchNo")));
        Page<FollowupSopBatch> raw = batchRepo.findAll(spec, page);
        if (raw.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, raw.getTotalElements());
        }
        List<String> batchNos = raw.getContent().stream().map(FollowupSopBatch::getBatchNo).toList();
        Map<String, List<Followup>> nodesByBatch = new LinkedHashMap<>();
        for (Followup f : followupRepo.findBySopBatchIdInOrderByPlanDateAsc(batchNos)) {
            nodesByBatch.computeIfAbsent(f.getSopBatchId(), k -> new ArrayList<>()).add(f);
        }
        LocalDate today = LocalDate.now(BIZ_TZ);
        List<BatchAgg> aggs = raw.getContent().stream()
                .map(b -> aggregate(b, nodesByBatch.getOrDefault(b.getBatchNo(), List.of()), today))
                .sorted(Comparator.comparing(BatchAgg::finished)
                        .thenComparing(BatchAgg::batch,
                                Comparator.comparing(FollowupSopBatch::getServiceDate).reversed()))
                .toList();
        return new PageImpl<>(aggs, pageable, raw.getTotalElements());
    }

    private BatchAgg aggregate(FollowupSopBatch b, List<Followup> nodes, LocalDate today) {
        int closed = 0;
        int overdue = 0;
        for (Followup f : nodes) {
            if (!FollowupService.ST_PENDING.equals(f.getStatus())) {
                closed++;
            } else if (f.getPlanDate().isBefore(today)) {
                overdue++;
            }
        }
        int total = nodes.isEmpty() ? b.getNodeCount() : nodes.size();
        boolean finished = total > 0 && closed == total;
        return new BatchAgg(b, nodes, total, closed, overdue, finished);
    }

    /**
     * 看板五键：进行中批次 / 已完结批次 / SOP 待办节点 / 超期节点（含已升级）/ 超期未升级（一键升级目标）。
     * 批次与节点均按门店数据域。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String storeCode) {
        Specification<FollowupSopBatch> batchSpec = batchScope(storeCode);
        long totalBatches = batchRepo.count(batchSpec);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Followup> root = cq.from(Followup.class);
        cq.select(root.get("sopBatchId")).distinct(true);
        Specification<Followup> activeSpec = followSpec(storeCode)
                .and((r, q, c) -> c.and(c.isNotNull(r.get("sopBatchId")),
                        c.equal(r.get("status"), FollowupService.ST_PENDING)));
        cq.where(activeSpec.toPredicate(root, cq, cb));
        List<String> activeBatchIds = em.createQuery(cq)
                .setMaxResults(MAX_BATCHES_FETCH).getResultList();
        long activeBatches = activeBatchIds.size();

        LocalDate today = LocalDate.now(BIZ_TZ);
        Specification<Followup> pendingSop = followSpec(storeCode)
                .and((r, q, c) -> c.and(c.isNotNull(r.get("sopBatchId")),
                        c.equal(r.get("status"), FollowupService.ST_PENDING)));
        long sopPending = followupRepo.count(pendingSop);
        long sopOverdue = followupRepo.count(pendingSop
                .and((r, q, c) -> c.lessThan(r.get("planDate"), today)));
        long needEscalation = followupRepo.count(pendingSop
                .and((r, q, c) -> c.and(c.lessThan(r.get("planDate"), today), c.isFalse(r.get("escalated")))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeBatches", activeBatches);
        out.put("finishedBatches", Math.max(0, totalBatches - activeBatches));
        out.put("sopPending", sopPending);
        out.put("sopOverdue", sopOverdue);
        out.put("needEscalation", needEscalation);
        return out;
    }

    /**
     * 一键升级：扫描本店超期未升级 SOP 节点（FIFO 50），逐条置 escalated + 通知店长 + 审计；
     * 与 60s 定时巡检共用 {@link FollowupSopEscalator}（通知幂等）。返回实际升级条数。
     */
    @Transactional
    public int escalateOverdue(String storeCode) {
        LocalDate today = LocalDate.now(BIZ_TZ);
        Specification<Followup> spec = followSpec(storeCode)
                .and((r, q, c) -> c.and(
                        c.isNotNull(r.get("sopBatchId")),
                        c.equal(r.get("status"), FollowupService.ST_PENDING),
                        c.lessThan(r.get("planDate"), today),
                        c.isFalse(r.get("escalated"))));
        List<Followup> overdue = followupRepo.findAll(spec,
                PageRequest.of(0, 50, Sort.by(Sort.Order.asc("planDate"), Sort.Order.asc("id"))))
                .getContent();
        String actor = DataScope.currentActor();
        for (Followup f : overdue) {
            escalator.escalate(f);
            followupRepo.save(f);
            audit.record("FOLLOWUP", f.getFollowupNo(), actor, "ESCALATE",
                    "{\"sopBatchId\":\"" + esc(f.getSopBatchId())
                            + "\",\"sopLabel\":\"" + esc(f.getSopLabel())
                            + "\",\"planDate\":\"" + f.getPlanDate() + "\"}");
        }
        return overdue.size();
    }

    // ==================== 内部 ====================

    /** 幂等懒初始化集团通用模板 + 内置四节点（未播种环境首次打开编排页时）。 */
    private void ensureDefaultTemplate() {
        String tplNo = FollowupSopDefaults.DEFAULT_TEMPLATE_NO;
        if (templateRepo.existsById(tplNo)) {
            return;
        }
        FollowupSopTemplate t = new FollowupSopTemplate();
        t.setTemplateNo(tplNo);
        t.setName(FollowupSopDefaults.DEFAULT_TEMPLATE_NAME);
        t.setStoreCode(null);
        t.setEnabled(true);
        t.setCreatedBy("system");
        templateRepo.save(t);
        int lineNo = 1;
        for (FollowupSopDefaults.NodeDef d : FollowupSopDefaults.NODES) {
            FollowupSopTemplateNode n = new FollowupSopTemplateNode();
            n.setTemplateNo(tplNo);
            n.setLineNo(lineNo++);
            n.setStage(d.stage());
            n.setLabel(d.label());
            n.setDayOffset(d.dayOffset());
            n.setMethod(d.method());
            n.setEnabled(true);
            nodeRepo.save(n);
        }
    }

    /** 全部节点按（天数升序、id 升序）重排行号 1..N。 */
    private void renumber() {
        List<FollowupSopTemplateNode> all = nodeRepo
                .findByTemplateNoOrderByLineNoAsc(FollowupSopDefaults.DEFAULT_TEMPLATE_NO);
        all.sort(Comparator.comparingInt(FollowupSopTemplateNode::getDayOffset)
                .thenComparing(FollowupSopTemplateNode::getId));
        int lineNo = 1;
        for (FollowupSopTemplateNode n : all) {
            n.setLineNo(lineNo++);
        }
        nodeRepo.saveAll(all);
    }

    private FollowupSopTemplateNode requireNode(Long nodeId) {
        if (nodeId == null) {
            throw badRequest("缺少节点 id");
        }
        FollowupSopTemplateNode n = nodeRepo.findById(nodeId)
                .orElseThrow(() -> notFound("SOP 模板节点不存在"));
        if (!FollowupSopDefaults.DEFAULT_TEMPLATE_NO.equals(n.getTemplateNo())) {
            throw notFound("SOP 模板节点不存在");
        }
        return n;
    }

    /** 批次门店数据域：显式他店码 404；叠加 JWT storeSpec 本店全见。 */
    private Specification<FollowupSopBatch> batchScope(String storeCode) {
        if (!blank(storeCode) && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<FollowupSopBatch> spec = DataScope.storeSpec("storeCode");
        if (!blank(storeCode)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        return spec;
    }

    private Specification<Followup> followSpec(String storeCode) {
        if (!blank(storeCode) && !DataScope.canReadStore(storeCode)) {
            throw notFound("数据不存在或无权查看");
        }
        Specification<Followup> spec = DataScope.storeSpec("storeCode");
        if (!blank(storeCode)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        return spec;
    }

    private static String requireLabel(String label) {
        String t = trimToNull(label);
        if (t == null) {
            throw badRequest("请填写节点名称");
        }
        return truncate(t, 64);
    }

    private static int requireDayOffset(Integer dayOffset) {
        if (dayOffset == null || dayOffset < 0 || dayOffset > 365) {
            throw badRequest("术后天数取值 0-365");
        }
        return dayOffset;
    }

    private String requireMethod(String method) {
        String t = trimToNull(method);
        if (t == null || !METHODS.contains(t)) {
            throw badRequest("非法回访方式: " + method);
        }
        return t;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
