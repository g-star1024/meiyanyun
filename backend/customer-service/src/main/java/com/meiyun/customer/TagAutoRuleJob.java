package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 标签自动化规则定时扫描（域①-257）。
 *
 * <p>范式复刻 txn-service {@code ApprovalSlaJob}：每分钟一轮、限批 50 条规则 FIFO、单条规则 try-catch、
 * 不加方法级 @Transactional（每条规则独立 runRule 内部事务，单条失败不回滚整批）、SLF4J 日志。
 * 仅执行「启用」的规则（按优先级升序）；每条规则结果若有实际打/撤标则落单条 TAG_AUTO/RULE_RUN 汇总审计。
 * 扫描源为内部定时任务，审计 actor 统一记为 SYSTEM。
 */
@Component
public class TagAutoRuleJob {

    private static final Logger log = LoggerFactory.getLogger(TagAutoRuleJob.class);
    private static final String ACTOR = "SYSTEM";

    private final TagAutoRuleService ruleService;
    private final AuditRecorder audit;

    public TagAutoRuleJob(TagAutoRuleService ruleService, AuditRecorder audit) {
        this.ruleService = ruleService;
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void scan() {
        List<TagAutoRule> rules = ruleService.listRules().stream()
                .filter(TagAutoRule::getEnabled).toList();
        if (rules.isEmpty()) return;
        // 按优先级升序，限批 50 条
        List<TagAutoRule> batch = rules.stream()
                .sorted(java.util.Comparator.comparingInt(TagAutoRule::getPriority)).limit(50).toList();
        for (TagAutoRule r : batch) {
            try {
                TagAutoRuleService.RuleRunResult res = ruleService.runRule(r);
                if (res.assigned() > 0 || res.revoked() > 0) {
                    audit.record("TAG_AUTO", r.getRuleId(), ACTOR, "RULE_RUN",
                            "{\"ruleId\":\"" + r.getRuleId() + "\",\"scanned\":" + res.scanned()
                                    + ",\"matched\":" + res.matched() + ",\"assigned\":" + res.assigned()
                                    + ",\"revoked\":" + res.revoked() + "}");
                }
                if (res.error() != null) {
                    log.warn("标签自动化规则执行异常 ruleId={} : {}", r.getRuleId(), res.error());
                }
                log.info("标签自动化规则扫描 ruleId={} 扫描{} 命中{} 打标{} 撤标{}", r.getRuleId(),
                        res.scanned(), res.matched(), res.assigned(), res.revoked());
            } catch (Exception ex) {
                // 单条规则异常不影响同批其余规则
                log.warn("标签自动化规则扫描兜底异常 ruleId={}: {}", r.getRuleId(), ex.getMessage());
            }
        }
    }
}
