package com.meiyun.store.reactivate;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class RcNoGenerator {

    private final ReactivateRepository rcRepo;

    public RcNoGenerator(ReactivateRepository rcRepo) {
        this.rcRepo = rcRepo;
    }

    public synchronized String nextRcNo() {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().replace("-", "");
        long max = 0L;
        try {
            max = rcRepo.maxSeqOfDay("RC-" + day + "-%");
        } catch (Exception ignore) {
        }
        return "RC-" + day + "-" + String.format("%06d", max + 1);
    }
}
