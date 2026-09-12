package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 券核销演示数据播种（M5-12）。
 *
 * <p>券模板表为空时补建两张进行中、带展示券码的券（WATER500 满减 / NEWBIE88 新客无门槛），
 * 并补四条核销流水（2 正常 + 1 重复 + 1 伪造，与前端 mock 基线一致）。
 * 幂等：券已存在或流水已存在则跳过；正常流水对应券 usedQty 同步到 2。
 *
 * <p><b>栈门控</b>：流水门店码 SST01 仅与 seed 栈主数据（01_master.sql，店名「上海徐汇店」
 * 同源同栈）自洽；prod/core 栈门店码为 ST-XX-NNN 且无 SST01，若把 SST 演示流水播进正式库
 * 会形成 store_name 解析不出来的幽灵门店数据。故仅在种子库（JDBC URL 含 meiyun_seed）播种，
 * 与 finance CommissionDataInitializer 同一门控约定；正式栈数据由页面真实核销产生。
 */
@Component
@Order(18)
public class CouponWriteoffDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CouponWriteoffDataInitializer.class);

    private final CouponTemplateRepository couponRepo;
    private final CouponWriteoffRecordRepository writeoffRepo;
    private final String datasourceUrl;

    public CouponWriteoffDataInitializer(CouponTemplateRepository couponRepo,
                                         CouponWriteoffRecordRepository writeoffRepo,
                                         @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.couponRepo = couponRepo;
        this.writeoffRepo = writeoffRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过券核销演示数据播种；正式栈核销流水由页面真实核销产生",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        seedCoupon("CPN-SEED-WATER", "WATER500", "水光满3000减500", "AMOUNT",
                50_000L, 300_000L, 500, LocalDate.now().minusDays(30), LocalDate.now().plusDays(60));
        seedCoupon("CPN-SEED-NEWBIE", "NEWBIE88", "新客88元体验券", "AMOUNT",
                8_800L, 0L, 1000, LocalDate.now().minusDays(20), LocalDate.now().plusDays(90));
        // 未核销的进行中折扣券：供扫码成功核销演示（faceValue=85 即 8.5 折）
        seedCoupon("CPN-SEED-VIP85", "VIP85", "会员85折券", "RATE",
                85L, 0L, 200, LocalDate.now().minusDays(10), LocalDate.now().plusDays(80));

        int seeded = 0;
        seeded += seedWriteoff("WR-SEED-0001", "WATER500", "CPN-SEED-WATER", "水光满3000减500",
                "孙佳宁", "138****2201", 368_000L, 50_000L, "OK", null, -2, "陈雅琳") ? 1 : 0;
        seeded += seedWriteoff("WR-SEED-0002", "NEWBIE88", "CPN-SEED-NEWBIE", "新客88元体验券",
                "赵雨晴", "139****8830", 58_000L, 8_800L, "OK", null, -1, "陈雅琳") ? 1 : 0;
        seeded += seedWriteoff("WR-SEED-0003", "WATER500", "CPN-SEED-WATER", "水光满3000减500",
                "孙佳宁", "138****2201", 368_000L, 0L, "DUPLICATE", "该券已核销，禁止重复使用", -1, "陈雅琳") ? 1 : 0;
        seeded += seedWriteoff("WR-SEED-0004", "FAKE999", null, "未知券",
                "匿名", "—", 128_000L, 0L, "FORGED", "券码不存在，疑似伪造", 0, "前台") ? 1 : 0;

        if (seeded > 0) {
            log.info("券核销演示数据播种完成：新增核销流水 {} 条", seeded);
        }
    }

    private void seedCoupon(String id, String code, String name, String type,
                            Long faceValue, Long threshold, int total,
                            LocalDate start, LocalDate end) {
        if (couponRepo.findById(id).isPresent()) {
            return;
        }
        if (couponRepo.findByCouponCodeIgnoreCase(code).isPresent()) {
            return;
        }
        CouponTemplate c = new CouponTemplate();
        c.setCouponId(id);
        c.setCouponName(name);
        c.setCouponType(type);
        c.setFaceValue(faceValue);
        c.setThreshold(threshold);
        c.setTotalQty(total);
        c.setIssuedQty(total);
        c.setUsedQty(0);
        c.setStatus("ACTIVE");
        c.setGrantScope("ALL");
        c.setCouponCode(code);
        c.setValidStart(start);
        c.setValidEnd(end);
        c.setCreatedAt(OffsetDateTime.now());
        couponRepo.save(c);
    }

    private boolean seedWriteoff(String id, String code, String couponId, String couponName,
                                 String customer, String phone, long orderFen, long discountFen,
                                 String status, String reason, int dayOffset, String operator) {
        if (writeoffRepo.findById(id).isPresent()) {
            return false;
        }
        CouponWriteoffRecord r = new CouponWriteoffRecord();
        r.setWriteoffId(id);
        r.setCouponCode(code);
        r.setCouponId(couponId);
        r.setCouponName(couponName);
        r.setCustomerName(customer);
        r.setCustomerPhone(phone);
        r.setStoreCode("SST01");
        r.setStoreName("上海徐汇店");
        r.setOrderAmountFen(orderFen);
        r.setDiscountFen(discountFen);
        r.setChannel("门店核销");
        r.setStatus(status);
        r.setReason(reason);
        r.setOperator(operator);
        r.setVerifiedAt(OffsetDateTime.now().withHour(14).withMinute(20).withSecond(0).withNano(0)
                .plusDays(dayOffset));
        writeoffRepo.save(r);
        if ("OK".equals(status) && couponId != null) {
            couponRepo.findById(couponId).ifPresent(c -> c.setUsedQty(Math.max(c.getUsedQty(), 1)));
        }
        return true;
    }
}
