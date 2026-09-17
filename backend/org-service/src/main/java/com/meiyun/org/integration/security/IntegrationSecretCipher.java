package com.meiyun.org.integration.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 外部依赖配置窗口密钥的 AES-GCM 加解密（P5-B57 卡2，算法与 ai ApiKeyCipher 一致）。
 * 主密钥来自 meiyun.integration.config-secret（env INTEGRATION_CONFIG_SECRET），经 SHA-256 派生 256 位密钥；
 * 密文格式 Base64(IV(12B) || 密文+tag)。密钥明文不落库、不进日志。
 */
@Component
public class IntegrationSecretCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public IntegrationSecretCipher(@Value("${meiyun.integration.config-secret}") String secret) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("外部集成配置主密钥初始化失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("外部集成密钥加密失败", e);
        }
    }

    public String decrypt(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("外部集成密钥解密失败（主密钥可能已变更）", e);
        }
    }

    /** 掩码回显：形如 abc1****wxyz；不足 8 位时仅显示 ****。 */
    public static String mask(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        if (plain.length() < 8) {
            return "****";
        }
        return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
    }
}
