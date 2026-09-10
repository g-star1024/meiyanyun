package com.meiyun.txn;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 病历号生成器：EM + yyyyMMdd + - + 6 位当日查库序号（仿 WriteoffNoGenerator，
 * synchronized 防并发重号）。P5-B27 起替换 ConsultPlanService 原 nanoTime 取号缺陷，
 * 两域共用同一号码池（emr_record）。修订号「源号-R{n}」不经本生成器。
 */
@Component
public class EmrNoGenerator {

    private final EmrRecordRepository emrRepo;

    public EmrNoGenerator(EmrRecordRepository emrRepo) {
        this.emrRepo = emrRepo;
    }

    public synchronized String nextEmrNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max;
        try {
            max = emrRepo.maxSeqOfDay("EM" + day + "-%");
        } catch (Exception e) {
            max = 0L;
        }
        return "EM" + day + "-" + String.format("%06d", max + 1);
    }
}
