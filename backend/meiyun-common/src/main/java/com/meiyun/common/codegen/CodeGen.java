package com.meiyun.common.codegen;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 统一编码生成器：全系统编码位一律走本类，禁各服务自造内存序列（铁律 6）。
 *
 * <p>两式：
 * <ul>
 *   <li>{@link #nextSeq} 序号式：前缀 + 固定位数序号（如客户 M001、员工 E015、房间 RM-001）。
 *       序号取「库内该前缀最大编码」的数字尾 +1，无历史从 1 起。</li>
 *   <li>{@link #nextDaily} 日期式：前缀 + yyyyMMdd + '-' + 6 位当日序号（如 CP20261005-000001）。
 *       序号取 DB 当日最大号 +1。</li>
 * </ul>
 *
 * <p>防重号语义：禁 AtomicLong 内存序列（重启/多实例会重号）——序号一律来自调用方注入的
 * 「库内最大值查询」函数；类级 synchronized 防单 JVM 并发同号，跨实例由 DB 唯一约束最终兜底。
 * 纯 Java 零 Spring 依赖，静态方法即取即用。
 */
public final class CodeGen {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int DAILY_SEQ_WIDTH = 6;

    private CodeGen() {
    }

    /**
     * 序号式：前缀 + 定宽序号（宽度不足前补 0）。
     *
     * @param prefix      编码前缀（如 "M"、"E"、"RM-"）
     * @param width       序号位宽（如 3 → M001）
     * @param maxOfPrefix 查询库内该前缀最大编码的函数（无历史返回 null）
     * @return 下一个编码；库内最大编码数字尾无法解析时从 1 重新起号
     */
    public static synchronized String nextSeq(String prefix, int width, Function<String, String> maxOfPrefix) {
        String max = maxOfPrefix.apply(prefix);
        long seq = 0;
        if (max != null && max.startsWith(prefix)) {
            try {
                seq = Long.parseLong(max.substring(prefix.length()).replace("-", ""));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return prefix + String.format("%0" + width + "d", seq + 1);
    }

    /**
     * 日期式：前缀 + yyyyMMdd + '-' + 6 位当日序号。
     *
     * @param prefix        编码前缀（如 "CP"、"CPN"）
     * @param maxNoOfPrefix 按 like 条件（前缀 + 当日 + "-%"）查询库内最大单号的函数（无当日历史返回 null）
     * @return 下一个单号
     */
    public static synchronized String nextDaily(String prefix, Function<String, String> maxNoOfPrefix) {
        String day = OffsetDateTime.now().format(DAY_FMT);
        String like = prefix + day + "-%";
        String max = maxNoOfPrefix.apply(like);
        long seq = 1;
        if (max != null && max.length() >= DAILY_SEQ_WIDTH) {
            try {
                seq = Long.parseLong(max.substring(max.length() - DAILY_SEQ_WIDTH)) + 1;
            } catch (NumberFormatException ignored) {
                seq = 1;
            }
        }
        return prefix + day + "-" + String.format("%0" + DAILY_SEQ_WIDTH + "d", seq);
    }
}
