package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * P5-B85 卡3 合同服务：签署快照落库＋生命周期状态机（草稿→生效中→已履行|已终止）。
 * v1 不走审批中心；全状态流转落 append-only 审计链（CONTRACT：CREATE/ACTIVATE/COMPLETE/TERMINATE）。
 * D6 联动：RepurchaseService 创建填 contractNo 时校验存在＋生效中＋客户匹配转出或接收方。
 * 边界：合同↔退款执行联动（冷静期/违约金自动判定）不在本批，列 04 backlog。
 */
@Service
public class ContractService {

    private static final Set<String> TYPES = Set.of("COURSE", "STORED_VALUE", "PACKAGE", "SERVICE");

    private final ContractRepository repo;
    private final AuditRecorder audit;
    private final ESignProvider esign;

    public ContractService(ContractRepository repo, AuditRecorder audit, ESignProvider esign) {
        this.repo = repo;
        this.audit = audit;
        this.esign = esign;
    }

    /** 新建草稿合同：单号服务端生成（HT+yyyyMMdd-6位，库内当日最大号递增，与 RP/DS 同口径）。 */
    @Transactional
    public Contract create(ContractCmd cmd) {
        if (!TYPES.contains(cmd.contractType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "合同类型仅支持：COURSE/STORED_VALUE/PACKAGE/SERVICE");
        }
        if (cmd.totalAmount() == null || cmd.totalAmount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同总额必须大于 0（分）");
        }
        int coolingDays = cmd.coolingDays() == null ? 7 : cmd.coolingDays();
        if (coolingDays < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "冷静期天数不得为负");
        }
        int penaltyRate = cmd.penaltyRate() == null ? 2000 : cmd.penaltyRate();
        if (penaltyRate < 0 || penaltyRate > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "违约金率须在 0~10000 基点之间（万分比）");
        }
        Contract c = new Contract();
        c.setContractNo(nextNo("HT"));
        c.setCustomerId(cmd.customerId());
        c.setStoreCode(cmd.storeCode());
        c.setContractType(cmd.contractType());
        c.setTitle(cmd.title());
        c.setSignDate(cmd.signDate() == null ? LocalDate.now() : cmd.signDate());
        c.setTotalAmount(cmd.totalAmount());
        c.setOrdersJson(cmd.ordersJson());
        c.setAssetsJson(cmd.assetsJson());
        c.setCoolingDays(coolingDays);
        c.setPenaltyRate(penaltyRate);
        c.setRefundTerms(cmd.refundTerms());
        c.setRemarks(cmd.remarks());
        c.setSignedBy(cmd.signedBy());
        c.setStatus("草稿");
        c.setCreatedAt(OffsetDateTime.now());
        try {
            repo.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "合同号冲突，请重试");
        }
        audit.record("CONTRACT", c.getContractNo(), DataScope.currentActor(),
                "CREATE", "{\"contractType\":\"" + cmd.contractType() + "\",\"totalAmount\":" + c.getTotalAmount() + "}");
        return c;
    }

    /** 列表：门店数据域收敛＋可选 customerId/status 过滤，创建时间倒序。 */
    public List<Contract> list(String customerId, String status) {
        Specification<Contract> spec = DataScope.storeSpec("storeCode");
        if (hasText(customerId)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (hasText(status)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return repo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
    }

    public Contract require(String no) {
        Contract c = repo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(c.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return c;
    }

    /** 草稿→生效中（仅生效中才可被 repurchase.contractNo 引用，D6）。 */
    @Transactional
    public Contract activate(String no) {
        Contract c = require(no);
        if (!"草稿".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "合同当前状态为「" + c.getStatus() + "」，仅草稿可生效");
        }
        // 棒⑧卡3：电子签接入位启用时，须客户签署回调落 SIGNED 方可生效（开关关闭对现存流程零影响）
        if (esign.enabled() && !"SIGNED".equals(c.getEsignStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "电子签已启用：合同须先完成客户电子签署方可生效（当前签署状态：" + c.getEsignStatus() + "）");
        }
        c.setStatus("生效中");
        c.setEffectiveAt(OffsetDateTime.now());
        repo.save(c);
        audit.record("CONTRACT", no, DataScope.currentActor(), "ACTIVATE", "{}");
        return c;
    }

    /** 生效中→已履行。 */
    @Transactional
    public Contract complete(String no) {
        Contract c = require(no);
        if (!"生效中".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "合同当前状态为「" + c.getStatus() + "」，仅生效中可履约完成");
        }
        c.setStatus("已履行");
        c.setCompletedAt(OffsetDateTime.now());
        repo.save(c);
        audit.record("CONTRACT", no, DataScope.currentActor(), "COMPLETE", "{}");
        return c;
    }

    /** 草稿|生效中→已终止（须终止原因；已履行/已终止不可再终止）。 */
    @Transactional
    public Contract terminate(String no, String terminateReason) {
        Contract c = require(no);
        if ("已履行".equals(c.getStatus()) || "已终止".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "合同当前状态为「" + c.getStatus() + "」，不可终止");
        }
        if (!hasText(terminateReason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "终止合同须填写终止原因（terminateReason）");
        }
        c.setStatus("已终止");
        c.setTerminateReason(terminateReason);
        c.setTerminatedAt(OffsetDateTime.now());
        repo.save(c);
        audit.record("CONTRACT", no, DataScope.currentActor(), "TERMINATE",
                "{\"reason\":\"" + terminateReason.replace("\"", "\\\"") + "\"}");
        return c;
    }

    /**
     * 棒⑧卡3 发起电子签署：草稿门禁＋签署状态幂等（SENT 早返回不重复下发厂商流程／SIGNED 拒绝）。
     * SKIPPED 诚实降级如实 409 中文外露；SENT 落厂商流程号＋审计。
     */
    @Transactional
    public Contract sendForSign(String no) {
        Contract c = require(no);
        if (!"草稿".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "合同当前状态为「" + c.getStatus() + "」，仅草稿可发起电子签署");
        }
        if ("SIGNED".equals(c.getEsignStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "合同已完成电子签署，无需重复发起");
        }
        if ("SENT".equals(c.getEsignStatus())) {
            return c;
        }
        ESignProvider.ESignSendResult result = esign.send(c);
        if (!result.sent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, result.detail());
        }
        c.setEsignFlowId(result.flowId());
        c.setEsignStatus("SENT");
        c.setEsignSentAt(OffsetDateTime.now());
        repo.save(c);
        audit.record("CONTRACT", no, DataScope.currentActor(), "ESIGN_SEND", "{}");
        return c;
    }

    /**
     * 棒⑧卡3 签署回调落库（系统身份无门店数据域，由验签回调控制器调用）：
     * flowId 与库内记录一致性核验防串号；SIGNED 重复回调幂等 dedup 早返回；
     * 事件白名单 SIGNED|DECLINED|FAILED，落签署字段＋审计（actor=esign-callback）。
     */
    @Transactional
    public ESignCallbackOutcome applySignCallback(String no, String flowId, String event,
                                                  String signerName, String signature) {
        Contract c = repo.findById(no).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "合同不存在"));
        if (c.getEsignFlowId() == null || !c.getEsignFlowId().equals(flowId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "签署流程号与库内记录不符，拒绝落库");
        }
        if ("SIGNED".equals(c.getEsignStatus()) && "SIGNED".equals(event)) {
            return new ESignCallbackOutcome(c, true);
        }
        switch (event) {
            case "SIGNED" -> {
                c.setEsignStatus("SIGNED");
                c.setEsignSignerName(signerName);
                c.setEsignSignature(signature);
                c.setEsignSignedAt(OffsetDateTime.now());
            }
            case "DECLINED" -> c.setEsignStatus("DECLINED");
            case "FAILED" -> c.setEsignStatus("FAILED");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知签署事件：" + event);
        }
        repo.save(c);
        audit.record("CONTRACT", no, "esign-callback", "ESIGN_" + event, "{}");
        return new ESignCallbackOutcome(c, false);
    }

    /** 回调落库出参：dedup=true 为重复签署完成回调幂等早返回（未重复落库/审计）。 */
    public record ESignCallbackOutcome(Contract contract, boolean dedup) {}

    /** B95-D7：库内化发号（库内当日最大号递增，进程重启/多实例不撞号；唯一索引兜底，撞号 409）。 */
    private synchronized String nextNo(String prefix) {
        String day = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long n = repo.maxSeqOfDay(prefix + day + "-%") + 1;
        return prefix + day + "-" + String.format("%06d", n);
    }

    private boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    public record ContractCmd(
            @NotBlank String customerId, @NotBlank String storeCode,
            @NotBlank String contractType, @NotBlank String title,
            LocalDate signDate, @NotNull Long totalAmount,
            String ordersJson, String assetsJson,
            Integer coolingDays, Integer penaltyRate,
            String refundTerms, String remarks, String signedBy) {}

    public record TerminateCmd(String terminateReason) {}
}
