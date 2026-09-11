package com.meiyun.txn;

import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 术后随访 SOP 自动排程（P5-B30）。
 *
 * <p>由 {@link ConsultPlanService#treatDone} 在治疗完成事务 AFTER_COMMIT 回调触发：
 * 按启用的 SOP 模板节点（术后 1/3/7/30 天）一次性生成一个 {@link FollowupSopBatch} 批次 +
 * 多条 {@link Followup} 随访节点，同批共享 batch_no。</p>
 *
 * <p>幂等：{@code followup_sop_batch.source_plan_id} 唯一约束 + 排程前 existsBySourcePlanId 查库防重，
 * 一张方案单只排一次（重复触发/回调重试直接跳过）。模板表无启用节点时回退内置默认四节点，
 * 保证术后随访在任何环境（含未播种的 prod 库）都不漏排。客户名经 customer-service 解析，
 * 解析失败（客户已删/服务暂不可用）降级为客户号，不阻断排程。</p>
 */
@Service
public class FollowupScheduler {

    private static final Logger log = LoggerFactory.getLogger(FollowupScheduler.class);
    private static final ZoneOffset BIZ_TZ = ZoneOffset.ofHours(8);

    /** 内置默认节点（与前端 DEFAULT_POST_OP_SOP 一致）：模板无启用节点时回退。 */
    private static final NodeDef[] DEFAULT_NODES = {
            new NodeDef("CARE_24H", "术后 24h 关怀", 1, "WECHAT"),
            new NodeDef("FOLLOWUP_3D", "第 3 天回访", 3, "PHONE"),
            new NodeDef("RECOVERY_7D", "第 7 天恢复评估", 7, "WECHAT"),
            new NodeDef("REVISIT_30D", "第 30 天复诊提醒", 30, "PHONE"),
    };

    private final FollowupSopBatchRepository batchRepo;
    private final FollowupRepository followupRepo;
    private final FollowupSopTemplateRepository templateRepo;
    private final FollowupSopTemplateNodeRepository nodeRepo;
    private final FollowupNoGenerator noGen;
    private final PlanRepository planRepo;
    private final PlanItemRepository itemRepo;
    private final ApptRefNameResolver names;
    /**
     * afterCommit 回调里事务同步仍处于 active 状态，REQUIRED 传播会加入「已提交的治疗事务」，
     * 导致批次/随访 save 静默丢弃。这里显式开 REQUIRES_NEW 物理事务，保证排程数据真正落库。
     */
    private final TransactionTemplate txNew;

    public FollowupScheduler(FollowupSopBatchRepository batchRepo, FollowupRepository followupRepo,
                             FollowupSopTemplateRepository templateRepo,
                             FollowupSopTemplateNodeRepository nodeRepo, FollowupNoGenerator noGen,
                             PlanRepository planRepo, PlanItemRepository itemRepo,
                             ApptRefNameResolver names, PlatformTransactionManager txManager) {
        this.batchRepo = batchRepo;
        this.followupRepo = followupRepo;
        this.templateRepo = templateRepo;
        this.nodeRepo = nodeRepo;
        this.noGen = noGen;
        this.planRepo = planRepo;
        this.itemRepo = itemRepo;
        this.names = names;
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    record NodeDef(String stage, String label, int dayOffset, String method) {}

    /**
     * 治疗完成后排程（AFTER_COMMIT 调用）。
     * afterCommit 回调里旧治疗事务虽已提交、但事务同步资源仍处于 active，@Transactional(REQUIRED)
     * 会复用那个不会再提交的事务，导致批次/随访 save 静默丢弃；故整体写库体显式包进
     * REQUIRES_NEW 物理事务。幂等：source_plan_id 唯一约束 + exists 查库（置于新事务内可见已提交数据）。
     * 方案单不存在/无治疗完成时间/无启用节点时跳过；并发重入由唯一约束兜底，异常交调用方记录。
     */
    public void scheduleForPlan(String planId) {
        if (planId == null || planId.isBlank()) return;
        ScheduleOutcome outcome = txNew.execute(status -> doSchedule(planId));
        if (outcome != null) {
            log.info("术后 SOP 排程完成 planId={} batch={} customer={} 节点{}个",
                    planId, outcome.batchNo(), outcome.customerId(), outcome.nodeCount());
        }
    }

    /** 排程写库体（运行在 REQUIRES_NEW 事务内）：幂等检查 + 建批次 + 建节点；无需写入时返回 null。 */
    private ScheduleOutcome doSchedule(String planId) {
        if (batchRepo.existsBySourcePlanId(planId)) {
            log.info("术后 SOP 已排程，跳过重复触发 planId={}", planId);
            return null;
        }
        ConsultPlan p = planRepo.findById(planId).orElse(null);
        if (p == null) {
            log.warn("术后 SOP 排程找不到方案单 planId={}", planId);
            return null;
        }
        if (p.getTreatedAt() == null) {
            log.warn("术后 SOP 排程方案单无治疗完成时间，跳过 planId={}", planId);
            return null;
        }
        List<NodeDef> nodes = resolveNodes();
        if (nodes.isEmpty()) {
            log.warn("术后 SOP 无启用节点且默认回退为空，跳过 planId={}", planId);
            return null;
        }

        String customerId = p.getCustomerId();
        String customerName = resolveCustomerName(customerId);
        String project = resolveProject(planId);
        LocalDate serviceDate = p.getTreatedAt().atZoneSameInstant(BIZ_TZ).toLocalDate();

        FollowupSopBatch batch = new FollowupSopBatch();
        batch.setBatchNo(nextBatchNo());
        batch.setSourcePlanId(planId);
        batch.setCustomerId(customerId);
        batch.setCustomerName(customerName);
        batch.setProject(project);
        batch.setRelatedOrderNo(p.getOrderNo());
        batch.setStoreCode(p.getStoreCode());
        batch.setServiceDate(serviceDate);
        batch.setNodeCount(nodes.size());
        batch.setCreatedBy(DataScope.currentActor());
        batchRepo.save(batch);

        for (NodeDef n : nodes) {
            Followup f = new Followup();
            f.setFollowupNo(noGen.nextFollowupNo());
            f.setCustomerId(customerId);
            f.setCustomerName(customerName);
            f.setProject(project);
            f.setRelatedOrderNo(p.getOrderNo());
            f.setStoreCode(p.getStoreCode());
            f.setServiceDate(serviceDate);
            f.setPlanDate(serviceDate.plusDays(n.dayOffset()));
            f.setMethod(n.method());
            f.setStatus("PENDING");
            f.setSopStage(n.stage());
            f.setSopLabel(n.label());
            f.setSopBatchId(batch.getBatchNo());
            f.setAdverseReaction(false);
            f.setNeedRevisit(false);
            f.setEscalated(false);
            followupRepo.save(f);
        }
        return new ScheduleOutcome(batch.getBatchNo(), customerId, nodes.size());
    }

    private record ScheduleOutcome(String batchNo, String customerId, int nodeCount) {}

    /** 取启用模板的启用节点（按行号升序）；无则回退内置默认四节点。 */
    private List<NodeDef> resolveNodes() {
        try {
            List<FollowupSopTemplate> templates = templateRepo.findByEnabledTrue();
            Set<NodeDef> defs = new LinkedHashSet<>();
            for (FollowupSopTemplate t : templates) {
                nodeRepo.findByTemplateNoAndEnabledTrueOrderByLineNoAsc(t.getTemplateNo())
                        .forEach(n -> defs.add(new NodeDef(n.getStage(), n.getLabel(),
                                n.getDayOffset(), n.getMethod())));
            }
            if (!defs.isEmpty()) return new ArrayList<>(defs);
        } catch (Exception e) {
            log.warn("术后 SOP 读取模板节点异常，回退默认节点: {}", e.getMessage());
        }
        return List.of(DEFAULT_NODES);
    }

    /** 项目名：方案单子项 itemName 去重拼接（/ 分隔），超长截断到 128。 */
    private String resolveProject(String planId) {
        try {
            List<String> names = new ArrayList<>(new LinkedHashSet<>(itemRepo
                    .findByPlanIdOrderByLineNoAsc(planId).stream()
                    .map(PlanItem::getItemName)
                    .filter(s -> s != null && !s.isBlank())
                    .toList()));
            if (names.isEmpty()) return "术后随访";
            String joined = String.join(" / ", names);
            return joined.length() <= 128 ? joined : joined.substring(0, 128);
        } catch (Exception e) {
            return "术后随访";
        }
    }

    private String resolveCustomerName(String customerId) {
        try {
            String name = names.customerNames(List.of(customerId)).get(customerId);
            if (name != null && !name.isBlank()) return name;
        } catch (Exception e) {
            log.warn("术后 SOP 解析客户名失败 customer={}: {}", customerId, e.getMessage());
        }
        return customerId;
    }

    /** 批次号：SOP + yyyyMMdd + - + 6 位查库序号（substring from 13，char_length=18）。 */
    private synchronized String nextBatchNo() {
        String day = LocalDate.now(BIZ_TZ).toString().replace("-", "");
        long max;
        try {
            max = batchRepo.maxSeqOfDay("SOP" + day + "-%");
        } catch (Exception e) {
            max = 0L;
        }
        return "SOP" + day + "-" + String.format("%06d", max + 1);
    }
}
