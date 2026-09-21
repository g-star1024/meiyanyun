package com.meiyun.store.handover;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class HjNoGenerator {

    private final HandoverRepository hoRepo;

    public HjNoGenerator(HandoverRepository hoRepo) {
        this.hoRepo = hoRepo;
    }

    public synchronized String nextHoNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = hoRepo.maxSeqOfDay("HJ-" + day + "-%");
        } catch (Exception ignore) {
        }
        return "HJ-" + day + "-" + String.format("%06d", max + 1);
    }
}
