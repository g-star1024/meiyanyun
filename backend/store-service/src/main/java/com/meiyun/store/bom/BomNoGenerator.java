package com.meiyun.store.bom;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 配方行号生成器：BOM + yyyyMMdd + - + 6 位当日序号（集中一处 synchronized，避免并发撞号）。
 */
@Component
public class BomNoGenerator {

    private final ProjectBomRepository bomRepo;

    public BomNoGenerator(ProjectBomRepository bomRepo) {
        this.bomRepo = bomRepo;
    }

    public synchronized String nextBomId() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = bomRepo.maxSeqOfDay("BOM" + day + "-%");
        } catch (Exception ignore) {
        }
        return "BOM" + day + "-" + String.format("%06d", max + 1);
    }
}
