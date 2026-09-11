package com.meiyun.txn;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 随访单号生成器：HF + yyyyMMdd + - + 6 位当日查库序号（池 followup，synchronized 防并发重号）。
 *
 * <p>仿 {@link EmrNoGenerator}：序号从第 12 位起（HF 2 位 + 8 位日期 + 1 连字符），
 * 基础件号 char_length=17；查库异常降级为 0，不阻断排程主链路。</p>
 */
@Component
public class FollowupNoGenerator {

    private final FollowupRepository followupRepo;

    public FollowupNoGenerator(FollowupRepository followupRepo) {
        this.followupRepo = followupRepo;
    }

    public synchronized String nextFollowupNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max;
        try {
            max = followupRepo.maxSeqOfDay("HF" + day + "-%");
        } catch (Exception e) {
            max = 0L;
        }
        return "HF" + day + "-" + String.format("%06d", max + 1);
    }
}
