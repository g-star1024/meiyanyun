package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 核销链三段漏斗启动播种（M5-12 券核销页漏斗）：表为空时幂等灌入三段。
 *
 * <p>固定口径：本月扫码核销 842 笔 = 正常 774 + 异常 9（伪造/重复/过期）+ 待处理 59，
 * 与 MarketingController 红线注释及前端漏斗展示一致。B22 前该表无播种器，
 * 全新环境漏斗返回空数组（页面三段全 0），生产库 3 行为历史手工补录、不可复现，本播种器补齐缺口。
 */
@Component
@Order(33)
public class CouponWriteoffChainDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CouponWriteoffChainDataInitializer.class);

    private final CouponWriteoffChainRepository chainRepo;

    public CouponWriteoffChainDataInitializer(CouponWriteoffChainRepository chainRepo) {
        this.chainRepo = chainRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (chainRepo.count() > 0) {
            log.info("核销链漏斗已存在（{} 段），跳过播种", chainRepo.count());
            return;
        }
        chainRepo.save(chain("CH-001", "正常核销", 774));
        chainRepo.save(chain("CH-002", "异常核销", 9));
        chainRepo.save(chain("CH-003", "待处理", 59));
        log.info("核销链漏斗播种完成：本月 842 笔 = 正常 774 + 异常 9 + 待处理 59");
    }

    private CouponWriteoffChain chain(String id, String segment, int cnt) {
        CouponWriteoffChain c = new CouponWriteoffChain();
        c.setChainId(id);
        c.setSegment(segment);
        c.setCnt(cnt);
        c.setPeriod("本月");
        return c;
    }
}
