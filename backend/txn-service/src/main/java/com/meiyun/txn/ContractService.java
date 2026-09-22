package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong seq = new AtomicLong(System.nanoTime() % 1_000_000);

    public ContractService(ContractRepository repo, AuditRecorder audit) {
        this.repo = repo;
        this.audit = audit;
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
        repo.save(c);
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

    private String nextNo(String prefix) {
        long n = seq.incrementAndGet() % 1_000_000;
        return prefix + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", n);
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
