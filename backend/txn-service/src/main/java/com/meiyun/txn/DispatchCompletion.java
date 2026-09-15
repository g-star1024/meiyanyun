package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 调度派单完成态联动（P5-B51 卡3，Backlog L143）。
 *
 * <p>由 {@link ConsultPlanService#treatDone} 在治疗完成事务 AFTER_COMMIT 回调触发：
 * 沿 plan.arrivalId → arrival.ahNo → arrival.apptNo 链找到该预约的活跃派单
 * （SCHEDULED / IN_PROGRESS），置 DONE + done_at 终态保留行。DONE 块仍随时间轴回显
 * （绿块标记已交付），但不再占用时段（重叠检测/前端忙格仅看活跃态），不可释放、
 * 该预约不可再派单。</p>
 *
 * <p>幂等：仅迁移活跃态派单，重复触发/回调重试时无活跃派单即跳过（no-op）。
 * 联动失败不回滚治疗主链路（调用方 try/catch 记录 warn），无派单的方案单正常跳过。</p>
 */
@Service
public class DispatchCompletion {

    private static final Logger log = LoggerFactory.getLogger(DispatchCompletion.class);

    /** 参与完成迁移的活跃占用状态（与 DispatchService.ACTIVE 口径一致）。 */
    private static final List<String> ACTIVE = List.of(
            DispatchAssignment.ST_SCHEDULED, DispatchAssignment.ST_IN_PROGRESS);

    private final PlanRepository planRepo;
    private final ArrivalRepository arrivalRepo;
    private final DispatchAssignmentRepository assignmentRepo;
    private final AuditRecorder audit;
    /**
     * afterCommit 回调里事务同步仍处于 active 状态，REQUIRED 传播会加入「已提交的治疗事务」，
     * 导致派单 save 静默丢弃（与 FollowupScheduler 同坑）。这里显式开 REQUIRES_NEW 物理事务，
     * 保证状态迁移真正落库。
     */
    private final TransactionTemplate txNew;

    public DispatchCompletion(PlanRepository planRepo, ArrivalRepository arrivalRepo,
                              DispatchAssignmentRepository assignmentRepo, AuditRecorder audit,
                              PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.arrivalRepo = arrivalRepo;
        this.assignmentRepo = assignmentRepo;
        this.audit = audit;
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 治疗完成后联动（AFTER_COMMIT 调用）。整体写库体显式包进 REQUIRES_NEW 物理事务；
     * 方案单无到店登记/登记无预约号/无活跃派单时跳过，返回迁移条数用于日志。
     */
    public void completeForPlan(String planId) {
        if (planId == null || planId.isBlank()) return;
        Integer done = txNew.execute(status -> doComplete(planId));
        if (done != null && done > 0) {
            log.info("调度派单完成态联动 planId={} 置DONE {}条", planId, done);
        }
    }

    /** 迁移写库体（运行在 REQUIRES_NEW 事务内）：链式解析预约号 + 活跃派单置 DONE。 */
    private int doComplete(String planId) {
        ConsultPlan p = planRepo.findById(planId).orElse(null);
        if (p == null || p.getArrivalId() == null || p.getArrivalId().isBlank()) return 0;
        Arrival arrival = arrivalRepo.findById(p.getArrivalId()).orElse(null);
        if (arrival == null || arrival.getApptNo() == null || arrival.getApptNo().isBlank()) return 0;
        List<DispatchAssignment> actives =
                assignmentRepo.findByApptNoAndStatusIn(arrival.getApptNo(), ACTIVE);
        if (actives.isEmpty()) return 0;

        OffsetDateTime now = OffsetDateTime.now();
        String actor = DataScope.currentActor();
        for (DispatchAssignment asg : actives) {
            asg.setStatus(DispatchAssignment.ST_DONE);
            asg.setDoneAt(now);
            assignmentRepo.save(asg);
            audit.record("DISPATCH", String.valueOf(asg.getId()), actor, "DONE",
                    "{\"apptNo\":\"" + arrival.getApptNo() + "\",\"planId\":\"" + planId + "\"}");
        }
        return actives.size();
    }
}
