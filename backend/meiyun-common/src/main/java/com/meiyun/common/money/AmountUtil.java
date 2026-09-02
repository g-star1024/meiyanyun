package com.meiyun.common.money;

/**
 * 金额与跨域换算工具（数据字典 §2.1 跨域换算系数 XD-01~XD-03）。
 * 全站金额统一以「分」为整数存储单位。
 */
public final class AmountUtil {

    private AmountUtil() {
    }

    /** 元 → 分（四舍五入到分）。 */
    public static long yuanToFen(double yuan) {
        return Math.round(yuan * 100.0);
    }

    /** 分 → 元（保留两位）。 */
    public static double fenToYuan(long fen) {
        return fen / 100.0;
    }

    /** XD-01：积分 → 元（100 积分 = ¥1）。 */
    public static double pointsToYuan(long points) {
        return points / 100.0;
    }

    /** XD-01：元 → 积分（100 积分 = ¥1）。 */
    public static long yuanToPoints(double yuan) {
        return Math.round(yuan * 100.0);
    }

    /** XD-02：退卡金额 = 余额 × 0.9（默认；可手动覆盖）。 */
    public static long defaultRefund(long balanceCents) {
        return Math.round(balanceCents * 0.9);
    }

    /** XD-03：手续费 = 余额 × 0.1（默认；禁忌退卡为 0；可手动覆盖）。 */
    public static long defaultFee(long balanceCents) {
        return Math.round(balanceCents * 0.1);
    }
}
