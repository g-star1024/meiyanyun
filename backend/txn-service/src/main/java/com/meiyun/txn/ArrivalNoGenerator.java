package com.meiyun.txn;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 接待/分诊域单号生成器：前缀(AH/TR) + yyyyMMdd + - + 6 位当日查库序号。
 * 集中一处 synchronized 取号，与队列号（门店当日自增整数）相互独立。
 */
@Component
public class ArrivalNoGenerator {

    private final ArrivalRepository arrivalRepo;
    private final TriageRepository triageRepo;

    public ArrivalNoGenerator(ArrivalRepository arrivalRepo, TriageRepository triageRepo) {
        this.arrivalRepo = arrivalRepo;
        this.triageRepo = triageRepo;
    }

    /** 到店登记号：AH + yyyyMMdd + - + 6 位。 */
    public synchronized String nextAhNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = arrivalRepo.maxSeqOfDay("AH" + day + "-%");
        } catch (Exception ignore) {
        }
        return "AH" + day + "-" + String.format("%06d", max + 1);
    }

    /** 分诊单号：TR + yyyyMMdd + - + 6 位。 */
    public synchronized String nextTrNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = triageRepo.maxSeqOfDay("TR" + day + "-%");
        } catch (Exception ignore) {
        }
        return "TR" + day + "-" + String.format("%06d", max + 1);
    }
}
