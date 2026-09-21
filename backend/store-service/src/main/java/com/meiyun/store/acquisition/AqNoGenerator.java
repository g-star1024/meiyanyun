package com.meiyun.store.acquisition;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class AqNoGenerator {

    private final AcquisitionRepository aqRepo;

    public AqNoGenerator(AcquisitionRepository aqRepo) {
        this.aqRepo = aqRepo;
    }

    public synchronized String nextAqNo() {
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().replace("-", "");
        long max = 0L;
        try {
            max = aqRepo.maxSeqOfDay("AQ-" + day + "-%");
        } catch (Exception ignore) {
        }
        return "AQ-" + day + "-" + String.format("%06d", max + 1);
    }
}
