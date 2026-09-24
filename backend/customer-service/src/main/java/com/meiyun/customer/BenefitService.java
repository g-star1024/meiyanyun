package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * B93 权益核销链路：免费护理（FREE_CARE）定义→钱包懒发放→核销写链路→360 读模型。
 *
 * <p>懒发放（D4）：读模型/核销双路径 getOrCreate——当期无钱包且等级次数>0 则建；
 * UK 撞行 catch 后重读（并发懒建安全）。懒建 INSERT 走 REQUIRES_NEW 物理事务：
 * PG 约束违例会中止当前事务（后续语句全报错），独立事务承载失败后只回滚新建体，
 * 外层事务不受传染可安全重读（TransactionTemplate 先例见 txn BomDeductService）。
 *
 * <p>核销写链路（D5/D9）：参数中文 400→client_request_id 幂等重放返既有（不重复扣次、不重复审计）→
 * 无钱包 NO_WALLET→剩余≤0 EXHAUSTED→行锁扣次 OK；异常落流水 ok=false 不抛 400
 * （仿 CouponWriteoffService.saveAbnormal），全动作 BENEFIT/WRITEOFF 审计（payload 含 before/after 剩余次数）。
 */
@Service
public class BenefitService {

    /** 本期唯一权益类型：每月免费护理（扩展预留）。 */
    public static final String FREE_CARE = "FREE_CARE";
    /** 周期/单号统一北京时区（与等级月聚合口径同源）。 */
    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int PROJECT_NAME_MAX = 40;

    private final MemberBenefitWalletRepository walletRepo;
    private final MemberBenefitWriteoffRepository writeoffRepo;
    private final MemberLevelRepository levelRepo;
    private final RefNameResolver nameResolver;
    private final AuditRecorder audit;
    private final TransactionTemplate txNew;

    public BenefitService(MemberBenefitWalletRepository walletRepo,
                          MemberBenefitWriteoffRepository writeoffRepo,
                          MemberLevelRepository levelRepo,
                          RefNameResolver nameResolver,
                          AuditRecorder audit,
                          PlatformTransactionManager txManager) {
        this.walletRepo = walletRepo;
        this.writeoffRepo = writeoffRepo;
        this.levelRepo = levelRepo;
        this.nameResolver = nameResolver;
        this.audit = audit;
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 当前权益周期（YYYY-MM，北京时区）。 */
    static String currentPeriod() {
        return YearMonth.now(BJ).toString();
    }

    // ---- 360 读模型（D7，customer:view 门槛；触发当期懒发放） ----

    @Transactional
    public CustomerBenefitsDTO listBenefits(Customer customer) {
        String period = currentPeriod();
        int monthQuota = quotaOf(customer.getLevel());
        getOrCreateWallet(customer, period, monthQuota);
        List<CustomerBenefitsDTO.BenefitWalletDTO> wallets =
                walletRepo.findByCustomerIdOrderByPeriodDesc(customer.getCustomerId()).stream()
                        .map(w -> new CustomerBenefitsDTO.BenefitWalletDTO(
                                w.getBenefitType(), w.getPeriod(), w.getLevelSnap(),
                                w.getTotalTimes(), w.getUsedTimes(),
                                w.getTotalTimes() - w.getUsedTimes(),
                                w.getPeriod().equals(period) ? monthQuota : w.getTotalTimes()))
                        .toList();
        List<CustomerBenefitsDTO.BenefitWriteoffDTO> flows = writeoffRepo
                .findByCustomerIdOrderByWriteoffIdDesc(customer.getCustomerId(), PageRequest.of(0, 20))
                .stream()
                .map(f -> new CustomerBenefitsDTO.BenefitWriteoffDTO(
                        f.getWriteoffNo(), f.getPeriod(), f.getProjectName(), f.getStatus(),
                        f.getReason(), f.getOperator(), f.getStoreName(), f.getCreatedAt()))
                .toList();
        return new CustomerBenefitsDTO(wallets, flows);
    }

    // ---- 核销写链路（D5，benefit:writeoff 门槛） ----

    @Transactional
    public WriteoffResult writeoff(Customer customer, String benefitType, String projectName,
                                   String clientRequestId) {
        String type = (benefitType == null || benefitType.isBlank()) ? FREE_CARE : benefitType.trim();
        if (!FREE_CARE.equals(type)) {
            throw new CustomerService.BadReq("暂不支持的权益类型：" + type + "（本期仅 FREE_CARE 免费护理）");
        }
        String project = projectName == null ? "" : projectName.trim();
        if (project.isEmpty()) throw new CustomerService.BadReq("护理项目名必填");
        if (project.length() > PROJECT_NAME_MAX) throw new CustomerService.BadReq("护理项目名须 ≤40 字");
        String reqId = clientRequestId == null ? "" : clientRequestId.trim();
        if (!UUID_RE.matcher(reqId).matches()) {
            throw new CustomerService.BadReq("clientRequestId 必填且须为 UUID（前端幂等键）");
        }
        if (customer.getMergedInto() != null) {
            throw new CustomerService.BadReq("客户档案已合并，请在主档案 " + customer.getMergedInto() + " 下核销");
        }

        // 幂等重放：同 clientRequestId 直接返既有流水（不重复扣次、不重复审计，仿 POINTS clientToken 口径）
        Optional<MemberBenefitWriteoff> replay = writeoffRepo.findByClientRequestId(reqId);
        if (replay.isPresent()) {
            return replayResult(replay.get());
        }

        String period = currentPeriod();
        String operator = actor();
        String storeCode = storeCodeOf(customer);
        String storeName = storeCode == null ? null
                : nameResolver.storeNames(List.of(storeCode)).get(storeCode);

        MemberBenefitWallet wallet = getOrCreateWallet(customer, period, quotaOf(customer.getLevel()));
        if (wallet == null) {
            return saveAbnormal(customer, period, type, project, reqId, operator, storeCode, storeName,
                    "NO_WALLET", "当前等级无免费护理权益");
        }

        // 行锁扣次：FOR UPDATE 串行化并发核销，锁内复核剩余防超扣
        MemberBenefitWallet locked = walletRepo.findForUpdate(wallet.getWalletId())
                .orElseThrow(() -> new CustomerService.NotFound("权益钱包不存在: " + wallet.getWalletId()));
        int remaining = locked.getTotalTimes() - locked.getUsedTimes();
        if (remaining <= 0) {
            return saveAbnormal(customer, period, type, project, reqId, operator, storeCode, storeName,
                    "EXHAUSTED", "当期免费护理次数已用完（" + locked.getTotalTimes() + " 次）");
        }
        locked.setUsedTimes(locked.getUsedTimes() + 1);
        walletRepo.save(locked);

        MemberBenefitWriteoff flow = newFlow(customer, period, type, project, reqId, operator,
                storeCode, storeName, "OK", null, locked.getLevelSnap());
        flow = writeoffRepo.saveAndFlush(flow);
        audit.record("BENEFIT", flow.getWriteoffNo(), operator, "WRITEOFF",
                "{\"customerId\":\"" + esc(customer.getCustomerId()) + "\",\"benefitType\":\"" + esc(type)
                        + "\",\"period\":\"" + esc(period) + "\",\"projectName\":\"" + esc(project)
                        + "\",\"status\":\"OK\",\"remainingBefore\":" + remaining
                        + ",\"remainingAfter\":" + (remaining - 1) + "}");
        return new WriteoffResult(true, flow.getWriteoffNo(), "OK", null, remaining - 1);
    }

    /**
     * client_request_id UK 并发撞行兜底（Controller catch DataIntegrityViolation 后调用）：
     * 撞行事务已整体回滚（未重复扣次），按幂等重放语义返既有流水。
     */
    public WriteoffResult replayResultByReqId(String clientRequestId) {
        if (clientRequestId == null) throw new CustomerService.BadReq("clientRequestId 必填");
        MemberBenefitWriteoff flow = writeoffRepo.findByClientRequestId(clientRequestId.trim())
                .orElseThrow(() -> new CustomerService.NotFound("核销流水不存在"));
        return replayResult(flow);
    }

    /** 生成下一个核销单号：BW+yyyyMMdd-6 位序号，基于库内当日最大号递增（synchronized 防并发重号，与 RC/MC 单号同口径）。 */
    private synchronized String nextWriteoffNo() {
        String day = LocalDate.now(BJ).toString().replace("-", "");
        long seq = writeoffRepo.maxSeqOfDay("BW" + day + "-%") + 1;
        return "BW" + day + "-" + String.format("%06d", seq);
    }

    /** 等级月配额：member_level 行读 freeCareTimesOf（列优先/兜底 Map 同口径）；行缺失兜底 LEVEL_FREE_CARE。 */
    private int quotaOf(String level) {
        return levelRepo.findById(level)
                .map(CustomerService::freeCareTimesOf)
                .orElse(CustomerService.LEVEL_FREE_CARE.getOrDefault(level, 0));
    }

    /**
     * 懒发放（D4）：当期无钱包且配额>0 则建（total_times=配额快照，level_snap=当期等级）。
     * 懒建 INSERT 走 REQUIRES_NEW 独立物理事务：UK 并发撞行只回滚新建体，外层事务不受 PG 事务中止传染，catch 后重读。
     */
    private MemberBenefitWallet getOrCreateWallet(Customer customer, String period, int quota) {
        Optional<MemberBenefitWallet> existing = walletRepo.findByCustomerIdAndPeriodAndBenefitType(
                customer.getCustomerId(), period, FREE_CARE);
        if (existing.isPresent()) return existing.get();
        if (quota <= 0) return null;
        MemberBenefitWallet draft = new MemberBenefitWallet();
        draft.setCustomerId(customer.getCustomerId());
        draft.setPeriod(period);
        draft.setBenefitType(FREE_CARE);
        draft.setLevelSnap(customer.getLevel());
        draft.setTotalTimes(quota);
        draft.setUsedTimes(0);
        try {
            return txNew.execute(status -> walletRepo.saveAndFlush(draft));
        } catch (DataIntegrityViolationException e) {
            return walletRepo.findByCustomerIdAndPeriodAndBenefitType(
                    customer.getCustomerId(), period, FREE_CARE).orElseThrow(() -> e);
        }
    }

    /** 异常落流水（NO_WALLET/EXHAUSTED）：不抛 400，返 ok=false；全动作审计（仿 CouponWriteoffService.saveAbnormal）。 */
    private WriteoffResult saveAbnormal(Customer customer, String period, String type, String project,
                                        String reqId, String operator, String storeCode, String storeName,
                                        String status, String reason) {
        MemberBenefitWriteoff flow = newFlow(customer, period, type, project, reqId, operator,
                storeCode, storeName, status, reason, customer.getLevel());
        flow = writeoffRepo.saveAndFlush(flow);
        audit.record("BENEFIT", flow.getWriteoffNo(), operator, "WRITEOFF",
                "{\"customerId\":\"" + esc(customer.getCustomerId()) + "\",\"benefitType\":\"" + esc(type)
                        + "\",\"period\":\"" + esc(period) + "\",\"projectName\":\"" + esc(project)
                        + "\",\"status\":\"" + esc(status) + "\",\"reason\":\"" + esc(reason) + "\"}");
        return new WriteoffResult(false, flow.getWriteoffNo(), status, reason, 0);
    }

    private MemberBenefitWriteoff newFlow(Customer customer, String period, String type, String project,
                                          String reqId, String operator, String storeCode, String storeName,
                                          String status, String reason, String levelSnap) {
        MemberBenefitWriteoff f = new MemberBenefitWriteoff();
        f.setWriteoffNo(nextWriteoffNo());
        f.setCustomerId(customer.getCustomerId());
        f.setPeriod(period);
        f.setBenefitType(type);
        f.setLevelSnap(levelSnap);
        f.setProjectName(project);
        f.setStatus(status);
        f.setReason(reason);
        f.setClientRequestId(reqId);
        f.setOperator(operator);
        f.setStoreCode(storeCode);
        f.setStoreName(storeName);
        return f;
    }

    /** 幂等重放结果：流水原样回（ok=status==OK），remaining 取该流水周期钱包现值（无钱包=null）。 */
    private WriteoffResult replayResult(MemberBenefitWriteoff flow) {
        Integer remaining = walletRepo.findByCustomerIdAndPeriodAndBenefitType(
                        flow.getCustomerId(), flow.getPeriod(), flow.getBenefitType())
                .map(w -> w.getTotalTimes() - w.getUsedTimes())
                .orElse(null);
        return new WriteoffResult("OK".equals(flow.getStatus()), flow.getWriteoffNo(),
                flow.getStatus(), flow.getReason(), remaining);
    }

    /** 操作人：DataScope.currentActor()（请求体不可信），空兜底 system（仿 CardLedgerService.actor）。 */
    private static String actor() {
        String a = DataScope.currentActor();
        return (a == null || a.isBlank()) ? "system" : a;
    }

    /** 门店口径（D10）：DataScope 当前门店，取不到/为空兜底客户归属门店。 */
    private static String storeCodeOf(Customer customer) {
        LoginUser u = DataScope.current();
        if (u != null && u.storeCode() != null && !u.storeCode().isBlank()) return u.storeCode();
        return customer.getStoreCode();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 核销出参：ok=false 时 status/reason 为异常态中文说明；remaining 为当期剩余次数（重放历史周期/无钱包可为 null）。 */
    public record WriteoffResult(boolean ok, String writeoffNo, String status, String reason, Integer remaining) {}
}
