package com.meiyun.txn;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 划扣域单号生成器：前缀(WO/WD) + yyyyMMdd + - + 6 位当日序号。
 * 集中一处 synchronized 生成，避免 M4 划扣记录与 M2 核销台任务各自取号在同表/跨表场景下撞号。
 */
@Component
public class WriteoffNoGenerator {

    private final WriteoffRepository writeoffRepo;
    private final WriteoffDeskTaskRepository wdRepo;

    public WriteoffNoGenerator(WriteoffRepository writeoffRepo, WriteoffDeskTaskRepository wdRepo) {
        this.writeoffRepo = writeoffRepo;
        this.wdRepo = wdRepo;
    }

    /** 划扣记录号：WO + yyyyMMdd + - + 6 位。 */
    public synchronized String nextWriteoffNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = writeoffRepo.maxSeqOfDay("WO" + day + "-%");
        } catch (Exception ignore) {
        }
        return "WO" + day + "-" + String.format("%06d", max + 1);
    }

    /** 核销台任务号：WD + yyyyMMdd + - + 6 位。 */
    public synchronized String nextWdNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = wdRepo.maxSeqOfDay("WD" + day + "-%");
        } catch (Exception ignore) {
        }
        return "WD" + day + "-" + String.format("%06d", max + 1);
    }
}
