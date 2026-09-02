package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 违禁词库启动播种（A1-04）：表为空时把原静态词库 {@link ForbiddenWords} 的四类默认词
 * 幂等灌入 forbidden_word 表，保证升级后红线不断档；已有数据（管理端维护过）则完全不动。
 */
@Component
@Order(20)
public class ForbiddenWordDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ForbiddenWordDataInitializer.class);

    private final ForbiddenWordRepository repo;

    public ForbiddenWordDataInitializer(ForbiddenWordRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repo.count() > 0) {
            log.info("违禁词库已有 {} 条，跳过默认词播种", repo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        int seeded = 0;
        for (Map.Entry<String, java.util.List<String>> e : ForbiddenWords.categories().entrySet()) {
            for (String word : e.getValue()) {
                ForbiddenWord w = new ForbiddenWord();
                w.setCategory(e.getKey());
                w.setWord(word);
                w.setEnabled(true);
                w.setCreatedAt(now);
                w.setUpdatedAt(now);
                repo.save(w);
                seeded++;
            }
        }
        log.info("违禁词库播种完成：默认词 {} 条（四类）", seeded);
    }
}
