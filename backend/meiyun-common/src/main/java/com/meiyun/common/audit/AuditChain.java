package com.meiyun.common.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 审计不可篡改链（数据字典 §4 / T1-04，等保三级）。
 *
 * <p>每条审计记录存 {@code prev_hash}（上一条的 cur_hash）与 {@code cur_hash}：
 * <pre>
 *   cur_hash = SHA256( prev_hash || '|' || payload || '|' || actor || '|' || action || '|' || createdAt )
 * </pre>
 * 任意一条被篡改，其后整条链的哈希将全部失配 → 可检测。
 *
 * <p>纯 java.base 实现，无第三方依赖。
 */
public final class AuditChain {

    private AuditChain() {
    }

    /** 创世哈希（链首 prev_hash）。 */
    public static String genesisHash() {
        return sha256("MEIYUN_AUDIT_GENESIS");
    }

    /**
     * 计算当前记录哈希。
     *
     * @param prevHash   上一条 cur_hash（链首用 {@link #genesisHash()}）
     * @param payload    JSON 业务载荷（由调用方序列化）
     * @param actor      操作人
     * @param action     动作
     * @param createdAt  ISO-8601 时间戳（建议 UTC）
     */
    public static String computeHash(String prevHash, String payload, String actor, String action, String createdAt) {
        String input = String.join("|",
                nullToEmpty(prevHash),
                nullToEmpty(payload),
                nullToEmpty(actor),
                nullToEmpty(action),
                nullToEmpty(createdAt));
        return sha256(input);
    }

    /**
     * 校验单条链接是否完整（用于事后审计巡检）。
     */
    public static boolean verifyLink(String prevHash, String payload, String actor, String action, String createdAt, String expectedCurHash) {
        if (expectedCurHash == null) {
            return false;
        }
        return computeHash(prevHash, payload, actor, action, createdAt).equals(expectedCurHash);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
