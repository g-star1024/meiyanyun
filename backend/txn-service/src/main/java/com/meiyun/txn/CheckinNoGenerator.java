package com.meiyun.txn;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 到店核销登记单号生成器：CI + yyyyMMdd + - + 6 位当日序号。
 * 集中一处 synchronized 生成，查库号池取当日最大序号 +1，查询异常容错从 1 起。
 */
@Component
public class CheckinNoGenerator {

    private final CheckinRecordRepository ciRepo;

    public CheckinNoGenerator(CheckinRecordRepository ciRepo) {
        this.ciRepo = ciRepo;
    }

    public synchronized String nextCiNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = ciRepo.maxSeqOfDay("CI" + day + "-%");
        } catch (Exception ignore) {
        }
        return "CI" + day + "-" + String.format("%06d", max + 1);
    }
}
