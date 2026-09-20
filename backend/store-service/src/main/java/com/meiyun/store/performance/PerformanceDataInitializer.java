package com.meiyun.store.performance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(72)
public class PerformanceDataInitializer implements ApplicationRunner {

    private static final String STORE_CODE = "SST01";

    private record BaseStaff(String name, String role, String title, String avatarLetter,
                             long target, long actual, int orders, int commissionBp,
                             String status, LocalDate joinedAt, List<Integer> trend) {}

    private static final List<BaseStaff> STAFF = List.of(
            new BaseStaff("林微", "CONSULTANT", "资深咨询师", "林", 200000, 246800, 38, 1000,
                    "ON_DUTY", LocalDate.of(2022, 3, 15),
                    List.of(168000, 182000, 175000, 201000, 223000, 246800)),
            new BaseStaff("顾屿", "DOCTOR", "主治医师", "顾", 180000, 198400, 52, 800,
                    "ON_DUTY", LocalDate.of(2021, 7, 1),
                    List.of(142000, 156000, 168000, 175000, 189000, 198400)),
            new BaseStaff("周敏", "BEAUTICIAN", "高级美容师", "周", 80000, 92300, 124, 800,
                    "ON_DUTY", LocalDate.of(2023, 1, 20),
                    List.of(62000, 70000, 75000, 81000, 88000, 92300)),
            new BaseStaff("苏婉", "CONSULTANT", "咨询师", "苏", 150000, 132600, 29, 600,
                    "ON_DUTY", LocalDate.of(2023, 9, 10),
                    List.of(98000, 108000, 118000, 124000, 128000, 132600)),
            new BaseStaff("陈珂", "DOCTOR", "注射医师", "陈", 160000, 154800, 47, 800,
                    "ON_DUTY", LocalDate.of(2022, 11, 5),
                    List.of(120000, 128000, 138000, 146000, 150000, 154800)),
            new BaseStaff("吴桐", "BEAUTICIAN", "美容师", "吴", 70000, 58200, 96, 600,
                    "LEAVE", LocalDate.of(2024, 2, 18),
                    List.of(42000, 48000, 52000, 55000, 57000, 58200)),
            new BaseStaff("何苗", "CONSULTANT", "初级咨询师", "何", 100000, 67500, 18, 600,
                    "PROBATION", LocalDate.of(2025, 5, 6),
                    List.of(0, 0, 28000, 42000, 56000, 67500))
    );

    private final PerfStaffRepository staffRepo;
    private final PerformanceService service;
    private final String datasourceUrl;

    public PerformanceDataInitializer(PerfStaffRepository staffRepo,
                                      PerformanceService service,
                                      @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.staffRepo = staffRepo;
        this.service = service;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) return;
        if (staffRepo.count() > 0) return;

        YearMonth thisMonth = YearMonth.now(PerformanceService.BIZ_ZONE);
        seedMonth(thisMonth, false);
        seedMonth(thisMonth.minusMonths(1), true);
    }

    private void seedMonth(YearMonth month, boolean previous) {
        String period = month.toString();
        for (BaseStaff b : STAFF) {
            long actual = previous ? b.trend().get(4) : b.actual();
            List<Integer> trend = previous ? previousTrend(b.trend()) : b.trend();
            service.seed(period, new PerformanceService.SeedStaff(
                    STORE_CODE, b.name(), b.role(), b.title(), b.avatarLetter(),
                    b.target(), actual, b.orders(), b.commissionBp(),
                    b.status(), b.joinedAt(), trend));
        }
    }

    private List<Integer> previousTrend(List<Integer> current) {
        List<Integer> out = new ArrayList<>();
        out.add(current.get(4));
        for (int i = 0; i < 5; i++) out.add(current.get(i));
        return out;
    }
}
