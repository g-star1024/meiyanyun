package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
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
import java.util.LinkedHashSet;
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
 *
 * <p>P6-B101 多模板纵深：集团通用模板（store_code 为 NULL）与门店自建模板并存；
 * 行号（lineNo）与术后天数（dayOffset）解耦——行号即编排顺序（新增追加行尾、删除保序补齐、
 * reorder 按入参数组序整体重排），天数仅作排程语义，增改节点不再按天数自动重排；
 * 模板可见性＝集团通用全门店可见 + 门店模板仅本店及上级角色可见（他店 404）。</p>
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

    public record NodeCmd(String templateNo, String label, Integer dayOffset, String method) {}

    /** 模板列表行（编排页模板选择器）：模板号/名称/归属门店（null=集团通用）/启用/节点数。 */
    public record TemplateRow(String templateNo, String name, String storeCode, boolean enabled, int nodeCount) {}

    /** 模板详情：模板头 + 全部节点（含停用，行号升序）。 */
    public record TemplateDetail(FollowupSopTemplate template, List<FollowupSopTemplateNode> nodes) {}

    // ==================== 模板编排 ====================

    /**
     * 取模板详情（全部节点含停用，按行号升序）；templateNo 缺省为集团默认模板。
     * 未播种环境（如 prod 首次打开）幂等懒初始化模板 + 内置四节点（system 动作，不落业务审计）。
     * 显式模板号须对当前用户可见（集团通用或本店自建），他店模板 404。
     */
    @Transactional
    public TemplateDetail getTemplate(String templateNo) {
        ensureDefaultTemplate();
        return detailOf(requireVisibleTemplate(templateNo).getTemplateNo());
    }

    /** 模板列表（编排页选择器）：集团通用 + 本店自建，创建时间正序（默认模板在最前）。 */
    @Transactional
    public List<TemplateRow> listTemplates() {
        ensureDefaultTemplate();
        return templateRepo.findAll(Sort.by(Sort.Order.asc("createdAt"))).stream()
                .filter(t -> t.getStoreCode() == null || DataScope.canReadStore(t.getStoreCode()))
                .map(t -> new TemplateRow(t.getTemplateNo(), t.getName(), t.getStoreCode(),
                        Boolean.TRUE.equals(t.getEnabled()),
                        (int) nodeRepo.countByTemplateNo(t.getTemplateNo())))
                .toList();
    }

    /**
     * 建门店模板（P6-B101）：名称必填 64 截断；门店码取 JWT（未绑定门店 400 中文）；
     * 模板号 SPT + yyyyMMdd + - + 当日 6 位序号（号池仿批次号）；节点自集团默认模板当前节点
     * 复制并全部启用，生成后在编排页自由增改删（门店模板节点不受内置节点删除保护）。
     */
    @Transactional
    public TemplateDetail createStoreTemplate(String name) {
        ensureDefaultTemplate();
        String tplName = requireName(name);
        LoginUser u = DataScope.current();
        String storeCode = u == null ? null : u.storeCode();
        if (blank(storeCode)) {
            throw badRequest("当前账号未绑定门店，无法创建门店模板");
        }
        String actor = DataScope.currentActor();
        FollowupSopTemplate t = new FollowupSopTemplate();
        t.setTemplateNo(nextTemplateNo());
        t.setName(tplName);
        t.setStoreCode(storeCode);
        t.setEnabled(true);
        t.setCreatedBy(actor);
        templateRepo.save(t);

        int lineNo = 1;
        List<FollowupSopTemplateNode> nodes = new ArrayList<>();
        for (FollowupSopTemplateNode d : nodeRepo
                .findByTemplateNoOrderByLineNoAsc(FollowupSopDefaults.DEFAULT_TEMPLATE_NO)) {
            FollowupSopTemplateNode n = new FollowupSopTemplateNode();
            n.setTemplateNo(t.getTemplateNo());
            n.setLineNo(lineNo++);
            n.setStage(d.getStage());
            n.setLabel(d.getLabel());
            n.setDayOffset(d.getDayOffset());
            n.setMethod(d.getMethod());
            n.setEnabled(true);
            nodes.add(n);
        }
        nodeRepo.saveAll(nodes);
        audit.record("SOP_TEMPLATE", t.getTemplateNo(), actor, "TEMPLATE_CREATE",
                "{\"name\":\"" + esc(tplName) + "\",\"storeCode\":\"" + esc(storeCode)
                        + "\",\"nodes\":" + nodes.size() + "}");
        return detailOf(t.getTemplateNo());
    }

    /**
     * 节点重排（P6-B101）：按入参 nodeIds 数组序重写行号 1..N（编排页上移/下移落盘）。
     * nodeIds 须与模板节点全集一致（缺漏/越集/重复 400 中文）；仅改行号，dayOffset 不动。
     */
    @Transactional
    public TemplateDetail reorder(String templateNo, List<Long> nodeIds) {
        FollowupSopTemplate t = requireVisibleTemplate(templateNo);
        if (nodeIds == null) {
            throw badRequest("请提供节点排序");
        }
        List<FollowupSopTemplateNode> all = nodeRepo.findByTemplateNoOrderByLineNoAsc(t.getTemplateNo());
        Map<Long, FollowupSopTemplateNode> byId = new LinkedHashMap<>();
        for (FollowupSopTemplateNode n : all) {
            byId.put(n.getId(), n);
        }
        if (nodeIds.size() != byId.size() || !byId.keySet().containsAll(nodeIds)
                || new LinkedHashSet<>(nodeIds).size() != nodeIds.size()) {
            throw badRequest("节点排序与模板节点不一致，请刷新后重试");
        }
        int lineNo = 1;
        List<FollowupSopTemplateNode> ordered = new ArrayList<>();
        for (Long id : nodeIds) {
            FollowupSopTemplateNode n = byId.get(id);
            n.setLineNo(lineNo++);
            ordered.add(n);
        }
        nodeRepo.saveAll(ordered);
        audit.record("SOP_TEMPLATE", t.getTemplateNo(), DataScope.currentActor(),
                "TEMPLATE_REORDER", "{\"nodes\":" + ordered.size() + "}");
        return detailOf(t.getTemplateNo());
    }

    /**
     * 新增自定义节点（stage=MANUAL）：名称必填、天数 0-365、方式白名单；templateNo 缺省为集团默认模板；
     * 追加行尾（lineNo=max+1，不按天数重排，编排顺序由 reorder 调整）。
     */
    @Transactional
    public TemplateDetail addNode(NodeCmd cmd) {
        ensureDefaultTemplate();
        FollowupSopTemplate t = requireVisibleTemplate(cmd.templateNo());
        String label = requireLabel(cmd.label());
        int dayOffset = requireDayOffset(cmd.dayOffset());
        String method = requireMethod(cmd.method());
        String actor = DataScope.currentActor();

        List<FollowupSopTemplateNode> existing = nodeRepo.findByTemplateNoOrderByLineNoAsc(t.getTemplateNo());
        int nextLine = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getLineNo() + 1;

        FollowupSopTemplateNode n = new FollowupSopTemplateNode();
        n.setTemplateNo(t.getTemplateNo());
        n.setLineNo(nextLine);
        n.setStage("MANUAL");
        n.setLabel(label);
        n.setDayOffset(dayOffset);
        n.setMethod(method);
        n.setEnabled(true);
        FollowupSopTemplateNode saved = nodeRepo.save(n);
        audit.record("SOP_TEMPLATE", t.getTemplateNo(), actor, "NODE_ADD",
                "{\"nodeId\":" + saved.getId()
                        + ",\"label\":\"" + esc(label) + "\",\"dayOffset\":" + dayOffset
                        + ",\"method\":\"" + method + "\"}");
        return detailOf(t.getTemplateNo());
    }

    /** 改节点：名称/天数/方式（内置与自定义均可改）；不存在/他店模板 404，参数白名单；改天数不再触发重排。 */
    @Transactional
    public TemplateDetail updateNode(Long nodeId, NodeCmd cmd) {
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
        payload.append('}');
        audit.record("SOP_TEMPLATE", n.getTemplateNo(), actor, "NODE_UPDATE",
                payload.toString());
        return detailOf(n.getTemplateNo());
    }

    /** 启停节点：停用后不参与新批次排程，历史批次不变。 */
    @Transactional
    public TemplateDetail toggleNode(Long nodeId, boolean enabled) {
        FollowupSopTemplateNode n = requireNode(nodeId);
        n.setEnabled(enabled);
        nodeRepo.save(n);
        audit.record("SOP_TEMPLATE", n.getTemplateNo(), DataScope.currentActor(),
                "NODE_TOGGLE", "{\"nodeId\":" + nodeId + ",\"enabled\":" + enabled + "}");
        return detailOf(n.getTemplateNo());
    }

    /**
     * 删除节点：集团默认模板仅 MANUAL 自定义节点可删（内置四阶段引导停用，中文 400）；
     * 门店自建模板节点均可删；删除后保序补齐行号 1..N（相对顺序不变）。
     */
    @Transactional
    public TemplateDetail deleteNode(Long nodeId) {
        FollowupSopTemplateNode n = requireNode(nodeId);
        if (FollowupSopDefaults.DEFAULT_TEMPLATE_NO.equals(n.getTemplateNo())
                && !"MANUAL".equals(n.getStage())) {
            throw badRequest("内置节点不可删除，可停用该节点（停用后不再生成新批次）");
        }
        String tplNo = n.getTemplateNo();
        nodeRepo.delete(n);
        compactLineNo(tplNo);
        audit.record("SOP_TEMPLATE", tplNo, DataScope.currentActor(),
                "NODE_DELETE", "{\"nodeId\":" + nodeId + ",\"label\":\"" + esc(n.getLabel()) + "\"}");
        return detailOf(tplNo);
    }

    /**
     * 恢复默认：清空当前模板全部节点（含自定义），按 {@link FollowupSopDefaults} 重建内置四节点并全部启用。
     * 模板头保留（含模板号），不重建。
     */
    @Transactional
    public TemplateDetail resetTemplate() {
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
            return detailOf(tplNo);
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
        return detailOf(tplNo);
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

    /** 删除节点后保序补齐行号 1..N（相对顺序不变，仅消除空洞）。 */
    private void compactLineNo(String templateNo) {
        List<FollowupSopTemplateNode> all = nodeRepo.findByTemplateNoOrderByLineNoAsc(templateNo);
        int lineNo = 1;
        boolean changed = false;
        for (FollowupSopTemplateNode n : all) {
            if (n.getLineNo() != lineNo) {
                n.setLineNo(lineNo);
                changed = true;
            }
            lineNo++;
        }
        if (changed) {
            nodeRepo.saveAll(all);
        }
    }

    /** 模板解析：缺省=集团默认模板；显式模板号须存在且可见（集团通用或本店自建，他店 404）。 */
    private FollowupSopTemplate requireVisibleTemplate(String templateNo) {
        String tplNo = blank(templateNo) ? FollowupSopDefaults.DEFAULT_TEMPLATE_NO : templateNo.trim();
        FollowupSopTemplate t = templateRepo.findById(tplNo)
                .orElseThrow(() -> notFound("SOP 模板不存在"));
        if (t.getStoreCode() != null && !DataScope.canReadStore(t.getStoreCode())) {
            throw notFound("SOP 模板不存在");
        }
        return t;
    }

    /** 模板详情装配：模板头 + 全部节点（含停用，行号升序）。 */
    private TemplateDetail detailOf(String templateNo) {
        FollowupSopTemplate t = templateRepo.findById(templateNo)
                .orElseThrow(() -> notFound("SOP 模板不存在"));
        return new TemplateDetail(t, nodeRepo.findByTemplateNoOrderByLineNoAsc(templateNo));
    }

    /** 门店模板号：SPT + yyyyMMdd + - + 6 位查库序号（号池与批次号同范式，异常回落 0 起）。 */
    private synchronized String nextTemplateNo() {
        String day = LocalDate.now(BIZ_TZ).toString().replace("-", "");
        long max;
        try {
            max = templateRepo.maxSeqOfDay("SPT" + day + "-%");
        } catch (Exception e) {
            max = 0L;
        }
        return "SPT" + day + "-" + String.format("%06d", max + 1);
    }

    /** 节点解析：按 id 查节点；其模板须对当前用户可见（集团通用或本店自建），他店模板节点 404。 */
    private FollowupSopTemplateNode requireNode(Long nodeId) {
        if (nodeId == null) {
            throw badRequest("缺少节点 id");
        }
        FollowupSopTemplateNode n = nodeRepo.findById(nodeId)
                .orElseThrow(() -> notFound("SOP 模板节点不存在"));
        FollowupSopTemplate t = templateRepo.findById(n.getTemplateNo())
                .orElseThrow(() -> notFound("SOP 模板节点不存在"));
        if (t.getStoreCode() != null && !DataScope.canReadStore(t.getStoreCode())) {
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

    private static String requireName(String name) {
        String t = trimToNull(name);
        if (t == null) {
            throw badRequest("请填写模板名称");
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
