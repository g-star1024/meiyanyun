package com.meiyun.store.inspection;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class InsNoGenerator {

    private final InspectionRepository insRepo;

    public InsNoGenerator(InspectionRepository insRepo) {
        this.insRepo = insRepo;
    }

    public synchronized String nextInsNo() {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().replace("-", "");
        long max = 0L;
        try {
            max = insRepo.maxSeqOfDay("INS-" + day + "-%");
        } catch (Exception ignore) {
        }
        return "INS-" + day + "-" + String.format("%06d", max + 1);
    }
}
