package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BOM 自动扣料编排（B10，DESIGN §6.3）：双签划扣事务<b>提交后</b>按项目配方回调 store 扣库，
 * 成功发耗材成本事件（TK-MATERIAL），失败登记 {@link BomDeductException}（PENDING）供门店追溯处理。
 *
 * <p>医疗红线：扣料失败<b>绝不阻断、不回滚划扣</b>——afterCommit 内所有异常内部消化（仅日志+异常单）；
 * 未配 BOM 的项目 store 返回 skipped=true，静默跳过、不登记异常；
 * bizRef=BOM:{writeoffId} 全链路幂等（store 侧 bizRef+SKU 去重、finance 侧 CONSUMABLE-COST: 幂等键），
 * 重试安全不双扣、不重复记账。
 */
@Service
public class BomDeductService {

    private static final Logger log = LoggerFactory.getLogger(BomDeductService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StoreConsumableClient storeClient;
    private final FinanceEventPublisher financeEvents;
    private final BomDeductExceptionRepository excRepo;
    private final AuditRecorder audit;
    /**
     * afterCommit 回调里事务同步仍处于 active 状态，REQUIRED 传播会加入「已提交的划扣事务」，
     * 导致 save/事件写入静默丢弃。这里显式开 REQUIRES_NEW 物理事务，保证异常单/成本事件真正落库。
     */
    private final TransactionTemplate txNew;

    public BomDeductService(StoreConsumableClient storeClient, FinanceEventPublisher financeEvents,
                            BomDeductExceptionRepository excRepo, AuditRecorder audit,
                            PlatformTransactionManager txManager) {
        this.storeClient = storeClient;
        this.financeEvents = financeEvents;
        this.excRepo = excRepo;
        this.audit = audit;
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ==================== 划扣提交后触发 ====================

    /**
     * 在划扣事务内注册 afterCommit 回调：事务成功提交后再触发 BOM 扣料（扣库/成本事件/异常登记
     * 均不在划扣事务内，失败绝不影响划扣）；无活动事务（不应出现）时兜底直接执行。
     */
    public void triggerAfterCommit(String writeoffId, String storeCode, String projectName, String operator) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("BOM 扣料触发时无活动事务，直接异步兜底执行 writeoffId={}", writeoffId);
            runDeduct(writeoffId, storeCode, projectName, operator);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runDeduct(writeoffId, storeCode, projectName, operator);
            }
        });
    }

    /**
     * afterCommit 实际执行体：无事务上下文（事务外），远程调用与异常登记整体 try/catch，
     * 任何异常都只记日志、绝不向上抛出（划扣已提交，不可受影响）。
     */
    public void runDeduct(String writeoffId, String storeCode, String projectName, String operator) {
        String bizRef = "BOM:" + writeoffId;
        try {
            StoreConsumableClient.BomDeductResult result =
                    storeClient.deductByProject(bizRef, storeCode, projectName, operator);
            if (result.skipped()) {
                log.info("BOM 自动扣料跳过（项目未配配方）writeoffId={} project={}", writeoffId, projectName);
                audit("BOM", bizRef, operator, "SKIP",
                        "{\"writeoffId\":\"" + writeoffId + "\",\"project\":\"" + esc(projectName)
                                + "\",\"reason\":\"未配置 BOM 配方，静默跳过\"}");
                return;
            }
            // afterCommit 回调里旧事务已提交但同步未解绑，成本事件与异常核销必须开独立新事务，否则静默丢弃
            txNew.executeWithoutResult(status -> {
                financeEvents.emitConsumableCost(bizRef, storeCode, "USE", result.totalAmountFen());
                // 既往 PENDING 异常单（如曾因库存不足登记）随本次成功自动核销
                excRepo.findByWriteoffId(writeoffId).ifPresent(e -> {
                    if (BomDeductException.ST_PENDING.equals(e.getStatus())) {
                        e.setStatus(BomDeductException.ST_RESOLVED);
                        e.setResolvedAt(OffsetDateTime.now());
                        e.setResolvedBy(operator);
                        e.setDetailJson(null);
                        excRepo.save(e);
                    }
                });
            });
            log.info("BOM 自动扣料成功 writeoffId={} project={} 成本={}分 明细行={}",
                    writeoffId, projectName, result.totalAmountFen(), result.lines().size());
            audit("BOM", bizRef, operator, "SUCCESS",
                    "{\"writeoffId\":\"" + writeoffId + "\",\"project\":\"" + esc(projectName)
                            + "\",\"totalAmountFen\":" + result.totalAmountFen()
                            + ",\"lineCount\":" + result.lines().size() + "}");
        } catch (Exception ex) {
            log.warn("BOM 自动扣料失败，登记异常单（不影响划扣）writeoffId={}: {}", writeoffId, ex.getMessage());
            try {
                registerFailure(writeoffId, storeCode, projectName, reasonOf(ex), detailOf(ex), operator);
            } catch (Exception saveEx) {
                log.error("BOM 异常单登记失败 writeoffId={}: {}", writeoffId, saveEx.getMessage());
            }
        }
    }

    // ==================== 异常处理（重试 / 手工标记） ====================

    /**
     * 重试扣料（异常清单操作）：PENDING 单按登记时的项目/门店重新回调 store。
     * 成功 → 发成本事件、置 RESOLVED；失败 → failCount+1、刷新原因，中文错误透传给操作人。
     * 幂等：store 侧 bizRef+SKU 去重（不双扣），finance 侧幂等键去重（不重复记账）。
     */
    @Transactional
    public BomDeductException retry(String excId, String operator) {
        BomDeductException e = requireException(excId);
        if (!BomDeductException.ST_PENDING.equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该异常单已是「已处理」状态，无需重试（" + e.getExcId() + "）");
        }
        String bizRef = "BOM:" + e.getWriteoffId();
        StoreConsumableClient.BomDeductResult result =
                storeClient.deductByProject(bizRef, e.getStoreCode(), e.getProjectName(), operator);
        if (result.skipped()) {
            // 配方已被停用/删除：自动扣料不再适用，提示改手工标记
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该项目当前未配置 BOM 配方，自动扣料不适用；如已走领用审批手工补单，请直接「标记已处理」");
        }
        financeEvents.emitConsumableCost(bizRef, e.getStoreCode(), "USE", result.totalAmountFen());
        e.setStatus(BomDeductException.ST_RESOLVED);
        e.setResolvedAt(OffsetDateTime.now());
        e.setResolvedBy(operator);
        e.setDetailJson(null);
        BomDeductException saved = excRepo.save(e);
        audit("BOM", e.getExcId(), operator, "RETRY_SUCCESS",
                "{\"writeoffId\":\"" + e.getWriteoffId() + "\",\"project\":\"" + esc(e.getProjectName())
                        + "\",\"totalAmountFen\":" + result.totalAmountFen() + "}");
        return saved;
    }

    /** 手工标记已处理（门店走领用审批手工补单/线下补料后销项）。 */
    @Transactional
    public BomDeductException markResolved(String excId, String operator) {
        BomDeductException e = requireException(excId);
        if (BomDeductException.ST_RESOLVED.equals(e.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该异常单已是「已处理」状态");
        }
        e.setStatus(BomDeductException.ST_RESOLVED);
        e.setResolvedAt(OffsetDateTime.now());
        e.setResolvedBy(operator);
        BomDeductException saved = excRepo.save(e);
        audit("BOM", e.getExcId(), operator, "MARK_RESOLVED",
                "{\"writeoffId\":\"" + e.getWriteoffId() + "\",\"manual\":true}");
        return saved;
    }

    // ==================== 查询 ====================

    /** 异常清单：数据域强制注入 + 状态过滤，最近登记在前。 */
    public List<BomDeductException> list(String status, String storeCode) {
        if (storeCode != null && !storeCode.isBlank() && !DataScope.canReadStore(storeCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        Specification<BomDeductException> spec = DataScope.storeSpec("storeCode");
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status.trim()));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode.trim()));
        }
        return excRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
    }

    // ==================== 内部 ====================

    /**
     * 失败登记：同 writeoffId 已有单则 failCount+1 刷新原因，否则新建 BEX 单。
     * afterCommit 场景下旧事务同步仍 active，必须显式开 REQUIRES_NEW 物理事务，INSERT 才会真正提交；
     * 审计为远程调用，放事务外执行。
     */
    private void registerFailure(String writeoffId, String storeCode, String projectName,
                                 String reason, String detailJson, String operator) {
        final String safeReason = truncate(reason, 256);
        BomDeductException saved = txNew.execute(status -> {
            BomDeductException e = excRepo.findByWriteoffId(writeoffId).orElseGet(() -> {
                BomDeductException n = new BomDeductException();
                n.setExcId(nextExcId());
                n.setWriteoffId(writeoffId);
                n.setStoreCode(storeCode);
                n.setProjectName(projectName);
                n.setFailCount(0);
                return n;
            });
            e.setReason(safeReason);
            e.setDetailJson(detailJson);
            e.setStatus(BomDeductException.ST_PENDING);
            e.setFailCount(e.getFailCount() + 1);
            return excRepo.save(e);
        });
        audit("BOM", saved.getExcId(), operator, "FAIL",
                "{\"writeoffId\":\"" + writeoffId + "\",\"store\":\"" + esc(storeCode)
                        + "\",\"project\":\"" + esc(projectName) + "\",\"reason\":\"" + esc(safeReason)
                        + "\",\"shortageLines\":" + shortageCount(detailJson)
                        + ",\"failCount\":" + saved.getFailCount() + "}");
    }

    /** 提取缺料明细 JSON（仅 BOM 业务拒绝路径有；服务不可用/未建档等为 null）。 */
    private static String detailOf(Exception ex) {
        return ex instanceof BomShortageException bse ? bse.detailJson() : null;
    }

    /** 审计 payload 用：缺料行数（明细为空记 0），保持 payload 为合法 JSON。 */
    private static int shortageCount(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return 0;
        try {
            var node = MAPPER.readTree(detailJson);
            return node.isArray() ? node.size() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private BomDeductException requireException(String excId) {
        BomDeductException e = excRepo.findById(excId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(e.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return e;
    }

    /** 异常单号：BEX + yyyyMMdd + - + 6 位当日序号。 */
    private synchronized String nextExcId() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = excRepo.maxSeqOfDay("BEX" + day + "-%");
        } catch (Exception ignore) {
        }
        return "BEX" + day + "-" + String.format("%06d", max + 1);
    }

    /** 提取异常中文原因（ResponseStatusException 透传 store 中文；其余兜底通用文案）。 */
    private static String reasonOf(Exception ex) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null && !rse.getReason().isBlank()) {
            return rse.getReason();
        }
        return "BOM 自动扣料失败：" + ex.getClass().getSimpleName();
    }

    private void audit(String bizType, String txnNo, String actor, String action, String payload) {
        try {
            audit.record(bizType, txnNo, actor == null ? "system" : actor, action, payload);
        } catch (Exception e) {
            log.warn("BOM 审计记录失败 txnNo={} action={}: {}", txnNo, action, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "BOM 自动扣料失败";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
