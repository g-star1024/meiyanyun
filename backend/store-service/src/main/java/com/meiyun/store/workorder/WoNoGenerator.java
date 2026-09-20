package com.meiyun.store.workorder;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class WoNoGenerator {

    private final WorkOrderRepository woRepo;

    public WoNoGenerator(WorkOrderRepository woRepo) {
        this.woRepo = woRepo;
    }

    public synchronized String nextWoNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = woRepo.maxSeqOfDay("WO-" + day + "-%");
        } catch (Exception ignore) {
        }
        return "WO-" + day + "-" + String.format("%06d", max + 1);
    }
}
