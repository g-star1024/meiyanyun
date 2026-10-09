package com.meiyun.finance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * F2 #21 outbox 哨兵服务：补齐 outbox 状态机的「自动推进」腿。
 *
 * <p>缺口：outbox_record 自 B3 引入英文状态机（PENDING→RECONCILED/DIFF→ADJUSTED）后，
 * 状态推进全靠人工对账端点；三方对账（reconcile/tripartite）是只读聚合报告，只提示
 * 「请人工挂 DIFF」不写 outbox；fin_setting.outbox_retry 配置有定义但无消费方。
 * 实证批⑧归档 30 条 outbox 全 PENDING 永不推进。本哨兵每小时扫描「超时仍 PENDING」
 * 的台账，逐条累加 retry_count 并记 error；超过 outbox_retry 上限自动挂 DIFF
 * （复用 {@link FundEntryService#reconcileOutbox}，落 FUND_RECONCILE 审计），进入人工调平流程。
 *
 * <p>超时窗口：fin_setting.reconcile_tn（对账周期 T+N，默认 1 天）——超过 T+N 天仍
 * PENDING 视为超时未对账。重试上限：fin_setting.outbox_retry（默认 3，0-10）。
 *
 * <p>设计取舍（方案 A 第 3 条「三方对账自动挂 DIFF」的落地方式）：三方对账是「按日×门店
 * 聚合净额」的只读视图，聚合差异无法精确映射到单条 outbox，且 GET 只读端点不应有写副作用；
 * 故自动推进由本哨兵承担（超时重试 + 超限挂 DIFF），三方对账保持只读佐证，二者目标一致——
 * 让 PENDING 不再永久停留。
 */
@Service
public class OutboxSentinelService {

    private static final Logger log = LoggerFactory.getLogger(OutboxSentinelService.class);
    private static final Long SETTING_ID = 1L;

    private final OutboxRepository outboxRepo;
    private final FinSettingRepository settingRepo;
    private final FundEntryService fundEntryService;

    public OutboxSentinelService(OutboxRepository outboxRepo, FinSettingRepository settingRepo,
                                 FundEntryService fundEntryService) {
        this.outboxRepo = outboxRepo;
        this.settingRepo = settingRepo;
        this.fundEntryService = fundEntryService;
    }

    /** 扫描结果：扫到超时 PENDING 数 / 本轮重试数 / 本轮挂 DIFF 数。 */
    public record ScanResult(int scanned, int retried, int markedDiff) {}

    /**
     * 跑一轮哨兵扫描。整批一个事务（台账量小，失败整批回滚下轮自愈）。
     * 返回统计供 Job 记日志。
     */
    @Transactional
    public ScanResult runOnce() {
        FinSetting s = settingRepo.findById(SETTING_ID).orElse(null);
        int tn = s != null && s.getReconcileTn() != null ? s.getReconcileTn() : 1;
        int maxRetry = s != null && s.getOutboxRetry() != null ? s.getOutboxRetry() : 3;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime cutoff = now.minusDays(tn);
        List<OutboxRecord> overdue = outboxRepo.findByStatusAndCreatedAtBefore("PENDING", cutoff);

        int retried = 0;
        int markedDiff = 0;
        for (OutboxRecord rec : overdue) {
            int retry = (rec.getRetryCount() == null ? 0 : rec.getRetryCount()) + 1;
            rec.setRetryCount(retry);
            if (retry > maxRetry) {
                rec.setError("哨兵第 " + retry + " 次扫描：超过对账周期 T+" + tn
                        + " 仍未对账，重试超上限（" + maxRetry + "），自动挂差异待人工调平");
                // 复用人工对账状态机（PENDING→DIFF），落 FUND_RECONCILE 审计；同一持久化上下文，与本批一起提交。
                fundEntryService.reconcileOutbox(rec.getOutboxId(), true,
                        "outbox 哨兵超时自动挂差异（重试 " + retry + " 次，上限 " + maxRetry + "）", "sentinel");
                markedDiff++;
            } else {
                rec.setError("哨兵第 " + retry + " 次扫描：超过对账周期 T+" + tn + " 仍未对账，待重试");
                outboxRepo.save(rec);
                retried++;
            }
        }

        log.info("outbox 哨兵扫描结束 超时PENDING={} 重试={} 挂DIFF={} (T+{}上限,重试上限{})",
                overdue.size(), retried, markedDiff, tn, maxRetry);
        return new ScanResult(overdue.size(), retried, markedDiff);
    }
}
