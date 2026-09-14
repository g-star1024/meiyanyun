package com.meiyun.store.procurement;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 采购单号生成器（B49 卡5，仿 BomNoGenerator 防重号模式）：
 * 前缀 PO + 8 位日期 + "-" + 6 位当日序号，如 PO20260914-000001。
 * 序号取 purchase_order 当日最大序号 +1（原生查询，不依赖内存自增），方法 synchronized 防并发重号。
 */
@Component
public class PoNoGenerator {

    private final PurchaseOrderRepository poRepo;

    public PoNoGenerator(PurchaseOrderRepository poRepo) {
        this.poRepo = poRepo;
    }

    public synchronized String nextPoNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        try {
            max = poRepo.maxSeqOfDay("PO" + day + "-%");
        } catch (Exception ignore) {
        }
        return "PO" + day + "-" + String.format("%06d", max + 1);
    }
}
