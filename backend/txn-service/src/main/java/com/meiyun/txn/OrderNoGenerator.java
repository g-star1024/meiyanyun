package com.meiyun.txn;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 订单号生成器（单例单锁）：OD + yyyyMMdd + - + 6 位序号（当日最大序号 +1）。
 *
 * <p>诊疗主线（医生签病历自动开单）、零售支线（/prescription 直开）、收银补录共用同一把锁，
 * 数据库 max 序号兜底，杜绝并发/重启重号撞主键。
 */
@Component
public class OrderNoGenerator {

    private final TxnOrderRepository orderRepo;

    public OrderNoGenerator(TxnOrderRepository orderRepo) {
        this.orderRepo = orderRepo;
    }

    public synchronized String nextOrderNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long seq = orderRepo.maxSeqOfDay("OD" + day + "-%") + 1;
        return "OD" + day + "-" + String.format("%06d", seq);
    }
}
