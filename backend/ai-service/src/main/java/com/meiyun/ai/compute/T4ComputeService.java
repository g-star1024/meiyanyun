package com.meiyun.ai.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiT4GpuNode;
import com.meiyun.ai.domain.AiT4GpuNodeRepository;
import com.meiyun.ai.domain.AiT4GpuQuota;
import com.meiyun.ai.domain.AiT4GpuQuotaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * T4 算力管理：GPU 节点监控 + 部门配额分配。
 *
 * 诚实口径：
 * 1. 节点遥测（vram_used/utilization/temperature/current_task/pod_name）为种子快照，
 *    无真实采集探针；updateGpuStatus 仅改状态，不联动伪造遥测数据；
 * 2. 成本模拟器 simulateCost 与型号单价表 GPU_MODEL_PRICE 纯前端保留（契约⑥），
 *    cost_per_hour 种子值与前端单价表一致（A100=28/H100=58/V100=16/T4=8）；
 * 3. 配额分配语义逐字对齐 mock allocateQuota：used/spent 从 0 起、status=ACTIVE，
 *    新配额置顶展示由前端适配层负责（后端按 quota_id 升序直返）。
 */
@Service
public class T4ComputeService {

    private static final int CODE_MAX = 40;
    private static final int DEPT_MAX = 64;
    private static final int PROJECT_MAX = 120;
    private static final int PERIOD_MAX = 16;
    private static final Set<String> GPU_STATUS = Set.of("IDLE", "BUSY", "OFFLINE", "RESERVED");

    private final AiT4GpuNodeRepository nodeRepo;
    private final AiT4GpuQuotaRepository quotaRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public T4ComputeService(AiT4GpuNodeRepository nodeRepo,
                            AiT4GpuQuotaRepository quotaRepo,
                            AuditRecorder audit) {
        this.nodeRepo = nodeRepo;
        this.quotaRepo = quotaRepo;
        this.audit = audit;
    }

    public record GpuView(String code, String name, String model, int vramTotal, int vramUsed,
                          int utilization, int temperature, String status, String currentTask,
                          String podName, BigDecimal costPerHour, String region,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record QuotaView(String code, String department, String project, int gpuHours,
                            int gpuHoursUsed, BigDecimal budget, BigDecimal spent,
                            String period, String status,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** 算力总览（前端 seed 一次拉取：gpus + quotas 双数组契约） */
    public record ComputeView(List<GpuView> gpus, List<QuotaView> quotas) {
    }

    public record AllocateCmd(String department, String project, Integer gpuHours,
                              BigDecimal budget, String period) {
    }

    public record UpdateStatusCmd(String status) {
    }

    @Transactional(readOnly = true)
    public ComputeView overview() {
        List<GpuView> gpus = nodeRepo.findAllByOrderByNodeIdAsc().stream()
                .map(this::toGpuView).toList();
        List<QuotaView> quotas = quotaRepo.findAllByOrderByQuotaIdAsc().stream()
                .map(this::toQuotaView).toList();
        return new ComputeView(gpus, quotas);
    }

    // 刻意不加方法级事务：save 由仓储自身事务提交，随后 findByCode 全新读回时间戳（照卡1 register 先例）。
    public QuotaView allocate(AllocateCmd cmd, String actor) {
        String department = normalizeDepartment(cmd == null ? null : cmd.department());
        String project = normalizeProject(cmd == null ? null : cmd.project());
        Integer gpuHours = normalizeGpuHours(cmd == null ? null : cmd.gpuHours());
        BigDecimal budget = normalizeBudget(cmd == null ? null : cmd.budget());
        String period = normalizePeriod(cmd == null ? null : cmd.period());
        if (quotaRepo.existsByDepartmentAndProjectAndPeriod(department, project, period)) {
            throw badRequest("该部门在此项目本周期的配额已存在（" + department + "/" + project + " " + period + "）");
        }

        AiT4GpuQuota q = new AiT4GpuQuota();
        q.setCode(nextCode());
        q.setDepartment(department);
        q.setProject(project);
        q.setGpuHours(gpuHours);
        q.setGpuHoursUsed(0);
        q.setBudget(budget);
        q.setSpent(BigDecimal.ZERO);
        q.setPeriod(period);
        q.setStatus("ACTIVE");
        AiT4GpuQuota saved = quotaRepo.save(q);
        audit.record("AI_T4_GPU_QUOTA", saved.getCode(), actor, "ALLOCATE",
                payload(Map.of("department", department, "project", project,
                        "gpuHours", gpuHours, "budget", budget, "period", period)));
        return quotaRepo.findByCode(saved.getCode()).map(this::toQuotaView)
                .orElseGet(() -> toQuotaView(saved));
    }

    /** GPU 状态变更：仅改状态（mock updateGpuStatus 的 patch 为前端内部便利，后端收窄）；不联动伪造遥测 */
    @Transactional
    public GpuView updateGpuStatus(String code, UpdateStatusCmd cmd, String actor) {
        AiT4GpuNode node = mustGetNode(code);
        String st = normalizeGpuStatus(cmd == null ? null : cmd.status());
        node.setStatus(st);
        nodeRepo.save(node);
        audit.record("AI_T4_GPU_NODE", node.getCode(), actor, "UPDATE_STATUS",
                payload(Map.of("status", st)));
        return toGpuView(node);
    }

    private AiT4GpuNode mustGetNode(String code) {
        String c = code == null ? "" : code.trim();
        return nodeRepo.findByCode(c)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "GPU 节点不存在（code=" + c + "）"));
    }

    /** 业务码分配：quota- 前缀 + 时间戳 36 进制 + 1 随机位，uk 冲突重试（照 mdl- 先例，8 次足够） */
    private String nextCode() {
        for (int i = 0; i < 8; i++) {
            String c = "quota-" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString(ThreadLocalRandom.current().nextInt(36), 36);
            if (c.length() <= CODE_MAX && !quotaRepo.existsByCode(c)) {
                return c;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "配额编码分配失败，请重试");
    }

    // ---------- 视图 ----------

    private GpuView toGpuView(AiT4GpuNode n) {
        return new GpuView(n.getCode(), n.getName(), n.getModel(),
                n.getVramTotal() == null ? 0 : n.getVramTotal(),
                n.getVramUsed() == null ? 0 : n.getVramUsed(),
                n.getUtilization() == null ? 0 : n.getUtilization(),
                n.getTemperature() == null ? 0 : n.getTemperature(),
                n.getStatus(), n.getCurrentTask(), n.getPodName(),
                n.getCostPerHour() == null ? BigDecimal.ZERO : n.getCostPerHour(),
                n.getRegion(), n.getCreatedAt(), n.getUpdatedAt());
    }

    private QuotaView toQuotaView(AiT4GpuQuota q) {
        return new QuotaView(q.getCode(), q.getDepartment(), q.getProject(),
                q.getGpuHours() == null ? 0 : q.getGpuHours(),
                q.getGpuHoursUsed() == null ? 0 : q.getGpuHoursUsed(),
                q.getBudget() == null ? BigDecimal.ZERO : q.getBudget(),
                q.getSpent() == null ? BigDecimal.ZERO : q.getSpent(),
                q.getPeriod(), q.getStatus(), q.getCreatedAt(), q.getUpdatedAt());
    }

    // ---------- 校验 ----------

    private String normalizeDepartment(String department) {
        if (department == null || department.isBlank()) {
            throw badRequest("部门不能为空");
        }
        String t = department.trim();
        if (t.length() > DEPT_MAX) {
            throw badRequest("部门不能超过 " + DEPT_MAX + " 字");
        }
        return t;
    }

    private String normalizeProject(String project) {
        if (project == null || project.isBlank()) {
            throw badRequest("项目名称不能为空");
        }
        String t = project.trim();
        if (t.length() > PROJECT_MAX) {
            throw badRequest("项目名称不能超过 " + PROJECT_MAX + " 字");
        }
        return t;
    }

    private Integer normalizeGpuHours(Integer gpuHours) {
        if (gpuHours == null || gpuHours <= 0) {
            throw badRequest("GPU 时长须为正整数");
        }
        return gpuHours;
    }

    private BigDecimal normalizeBudget(BigDecimal budget) {
        if (budget == null || budget.signum() < 0) {
            throw badRequest("预算不能为负");
        }
        return budget;
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw badRequest("统计周期不能为空");
        }
        String t = period.trim();
        if (t.length() > PERIOD_MAX) {
            throw badRequest("统计周期不能超过 " + PERIOD_MAX + " 字");
        }
        return t;
    }

    private String normalizeGpuStatus(String status) {
        if (status == null || status.isBlank()) {
            throw badRequest("GPU 状态不能为空");
        }
        String t = status.trim().toUpperCase();
        if (!GPU_STATUS.contains(t)) {
            throw badRequest("GPU 状态仅支持 IDLE（空闲）/ BUSY（繁忙）/ OFFLINE（离线）/ RESERVED（预留）");
        }
        return t;
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
