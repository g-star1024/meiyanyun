package com.meiyun.ai.security;

import com.meiyun.ai.domain.AiSensitiveHit;
import com.meiyun.ai.domain.AiSensitiveHitRepository;
import com.meiyun.ai.domain.AiSensitiveWord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 敏感词命中落库：独立事务（REQUIRES_NEW）提交。
 * 调用链 FeatureInvokeService.invoke 刻意无方法级事务且命中后随即抛 400，
 * 命中记录必须在异常抛出前独立提交，不随外层回滚（与成功/失败调用日志独立提交同范式）。
 */
@Component
public class SensitiveHitRecorder {

    private static final int SNIPPET_MAX = 500;

    private final AiSensitiveHitRepository hitRepo;

    public SensitiveHitRecorder(AiSensitiveHitRepository hitRepo) {
        this.hitRepo = hitRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AiSensitiveWord w, String featureCode, String context,
                       String staffId, String staffName, String storeCode) {
        AiSensitiveHit hit = new AiSensitiveHit();
        hit.setWordId(w.getWordId());
        hit.setWord(w.getWord().trim());
        hit.setCategory(w.getCategory());
        hit.setFeatureCode(featureCode);
        hit.setStaffId(staffId);
        hit.setStaffName(staffName);
        hit.setStoreCode(storeCode);
        if (context != null) {
            hit.setContextSnippet(context.length() > SNIPPET_MAX ? context.substring(0, SNIPPET_MAX) : context);
        }
        hit.setFalsePositive(false);
        hitRepo.save(hit);
    }
}
