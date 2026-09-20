package com.meiyun.store.wastage;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class WsNoGenerator {

    private final WastageRepository wsRepo;

    public WsNoGenerator(WastageRepository wsRepo) {
        this.wsRepo = wsRepo;
    }

    public synchronized String nextWsNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = wsRepo.maxSeqOfDay("WS-" + day + "-%");
        } catch (Exception ignore) {
        }
        return "WS-" + day + "-" + String.format("%06d", max + 1);
    }
}
