package com.meiyun.store.requisition;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class RqNoGenerator {

    private final RequisitionRepository rqRepo;

    public RqNoGenerator(RequisitionRepository rqRepo) {
        this.rqRepo = rqRepo;
    }

    public synchronized String nextRqNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = rqRepo.maxSeqOfDay("RQ-" + day + "-%");
        } catch (Exception ignore) {
        }
        return "RQ-" + day + "-" + String.format("%06d", max + 1);
    }
}
