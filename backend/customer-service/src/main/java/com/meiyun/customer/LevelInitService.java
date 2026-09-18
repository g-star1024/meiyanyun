package com.meiyun.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会员等级历史基线补种（域①-B62 卡1，CARD-C）。
 *
 * <p>customer/member_level 为 JPA ddl-auto=update 派生，Flyway 严禁 INSERT 引用，故上线前存量客户的
 * 当前等级无历史行可考。{@link #ensureInitialized()} 对 customer_level_history 中「完全无记录」的客户，
 * 按主档当前 level 补一条 LEVEL_INIT：from_level=NULL、to_level=当前等级、
 * changed_at=customer.created_at（保护期自建档月起算）。已有任意历史行（含 LEVEL_INIT）的客户不重补，
 * distinct 集差天然幂等。分批 500 客户、每批经 {@link LevelHistoryRecorder#saveInitBatch} 独立事务提交，
 * 单批失败不回滚已补种批次；不阻塞主流程。
 */
@Service
public class LevelInitService {

    private static final Logger log = LoggerFactory.getLogger(LevelInitService.class);
    private static final int BATCH = 500;
    static final String INIT_REASON = "历史等级基线（上线前生效时点不可考，保护期自建档月起算）";

    private final CustomerRepository customerRepo;
    private final CustomerLevelHistoryRepository historyRepo;
    private final LevelHistoryRecorder recorder;

    public LevelInitService(CustomerRepository customerRepo,
                            CustomerLevelHistoryRepository historyRepo,
                            LevelHistoryRecorder recorder) {
        this.customerRepo = customerRepo;
        this.historyRepo = historyRepo;
        this.recorder = recorder;
    }

    /** 幂等补种：返回本轮实际新增的 LEVEL_INIT 行数（0=已全部有基线/无客户）。 */
    public int ensureInitialized() {
        List<Customer> all = customerRepo.findAll();
        if (all.isEmpty()) return 0;

        List<String> allIds = all.stream().map(Customer::getCustomerId).toList();
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < allIds.size(); i += BATCH) {
            existing.addAll(historyRepo.findExistingCustomerIds(allIds.subList(
                    i, Math.min(i + BATCH, allIds.size()))));
        }

        int seeded = 0;
        List<CustomerLevelHistory> batch = new ArrayList<>();
        for (Customer c : all) {
            if (existing.contains(c.getCustomerId())) continue;
            CustomerLevelHistory h = new CustomerLevelHistory();
            h.setCustomerId(c.getCustomerId());
            h.setFromLevel(null);
            h.setToLevel(c.getLevel());
            h.setChangeSource(CustomerLevelHistory.SOURCE_LEVEL_INIT);
            h.setReason(INIT_REASON);
            h.setChangedAt(c.getCreatedAt());
            batch.add(h);
            if (batch.size() >= BATCH) {
                recorder.saveInitBatch(batch);
                seeded += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            recorder.saveInitBatch(batch);
            seeded += batch.size();
        }
        if (seeded > 0) log.info("会员等级历史基线补种完成：新增 LEVEL_INIT {} 行", seeded);
        return seeded;
    }
}
