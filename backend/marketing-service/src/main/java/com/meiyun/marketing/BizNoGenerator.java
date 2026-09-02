package com.meiyun.marketing;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 业务单据号生成：前缀 + yyyyMMdd + '-' + 6 位当日序号。
 * 序号取 DB 当日最大号 +1（禁 AtomicLong 内存序列——重启/多实例会重号），synchronized 防并发同号。
 * 前缀：CP 活动 / CPN 券模板 / GR 发券记录。
 */
@Component
public class BizNoGenerator {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public synchronized String next(String prefix, Function<String, String> maxNoOfPrefix) {
        String day = OffsetDateTime.now().format(DAY_FMT);
        String like = prefix + day + "-%";
        String max = maxNoOfPrefix.apply(like);
        long seq = 1;
        if (max != null && max.length() >= 6) {
            try {
                seq = Long.parseLong(max.substring(max.length() - 6)) + 1;
            } catch (NumberFormatException ignored) {
                seq = 1;
            }
        }
        return prefix + day + "-" + String.format("%06d", seq);
    }
}
