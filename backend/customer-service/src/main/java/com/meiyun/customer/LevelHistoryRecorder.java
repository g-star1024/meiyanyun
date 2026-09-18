package com.meiyun.customer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会员等级变更历史留痕单点（域①-B62 卡1）。
 *
 * <p>三条调级路径（{@link CustomerLevelHistory#SOURCE_MANUAL} 手工调级 /
 * {@link CustomerLevelHistory#SOURCE_AUTO_UPGRADE} 自动升级 /
 * {@link CustomerLevelHistory#SOURCE_AUTO_DOWNGRADE} 自动降级）一律经本 Bean 的
 * {@link #record} 落 customer_level_history，保证 append-only 留痕不遗漏；
 * {@link CustomerLevelHistory#SOURCE_LEVEL_INIT} 基线补种由 {@link LevelInitService} 单独走 saveAll。
 *
 * <p>独立 Bean 承载 {@code @Transactional}：被 {@code CustomerService} 注入后经 Spring 代理调用，
 * 与调用方自身事务共事务（默认 REQUIRED），调级成功与历史行同生共死；同时规避同类自调用导致事务切面失效。
 */
@Component
public class LevelHistoryRecorder {

    private final CustomerLevelHistoryRepository historyRepo;

    public LevelHistoryRecorder(CustomerLevelHistoryRepository historyRepo) {
        this.historyRepo = historyRepo;
    }

    /** 单条留痕（同事务）：from 可空（仅 LEVEL_INIT），changedAt 缺省取当前时间。 */
    @Transactional
    public CustomerLevelHistory record(String customerId, String fromLevel, String toLevel,
                                       String source, String reason, OffsetDateTime changedAt) {
        CustomerLevelHistory h = new CustomerLevelHistory();
        h.setCustomerId(customerId);
        h.setFromLevel(fromLevel);
        h.setToLevel(toLevel);
        h.setChangeSource(source);
        h.setReason(reason);
        h.setChangedAt(changedAt != null ? changedAt : OffsetDateTime.now());
        return historyRepo.save(h);
    }

    /** 基线补种专用：分批 {@code saveAll}（由 {@link LevelInitService} 在独立事务内调用）。 */
    @Transactional
    public List<CustomerLevelHistory> saveInitBatch(List<CustomerLevelHistory> batch) {
        return historyRepo.saveAll(batch);
    }

    /**
     * 批量取多客户的保护期锚点（最近一次「进入当前等级」、且来源为 LEVEL_INIT/AUTO_UPGRADE/MANUAL 的记录）。
     * 入参 idToLevel 为 customerId → 当前等级；返回 customerId → 锚点历史。
     * 自动降级自身不作为保护期锚点（同一跑批内一次只降一级，连续不达标下轮再降）；to_level 必须等于当前等级。
     */
    public Map<String, CustomerLevelHistory> latestEntryAnchors(Map<String, String> idToLevel) {
        Map<String, CustomerLevelHistory> anchors = new HashMap<>();
        if (idToLevel.isEmpty()) return anchors;
        for (CustomerLevelHistory h : historyRepo.findByCustomerIdIn(idToLevel.keySet())) {
            if (CustomerLevelHistory.SOURCE_AUTO_DOWNGRADE.equals(h.getChangeSource())) continue;
            if (!h.getToLevel().equals(idToLevel.get(h.getCustomerId()))) continue;
            CustomerLevelHistory prev = anchors.get(h.getCustomerId());
            if (prev == null || h.getChangedAt().isAfter(prev.getChangedAt())) {
                anchors.put(h.getCustomerId(), h);
            }
        }
        return anchors;
    }
}
