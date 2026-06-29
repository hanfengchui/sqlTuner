package com.codex.sqltuner.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 对称加密支持：API Key 等敏感字段落盘前加密，读取时解密。
 * Java 8 兼容：使用 AES/GCM/NoPadding（JDK8 内置）。
 * 密钥来源优先级：环境变量 SQL_TUNER_DATA_KEY（base64 32 字节）> 机器绑定派生 key。
 * 机器绑定 key 基于 user.home + 固定盐派生，保证同一机器重启可解密，
 * 但换机器/换目录拿到的是密文，不构成可直接使用的凭据泄露。
 */
@Component
public class CryptoSupport {
    private static final Logger log = LoggerFactory.getLogger(CryptoSupport.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String ENV_KEY = "SQL_TUNER_DATA_KEY";
    private final SecretKeySpec keySpec;
    private final SecureRandom random;

    public CryptoSupport() {
        this.keySpec = resolveKey();
        this.random = new SecureRandom();
    }

    private SecretKeySpec resolveKey() {
        String env = System.getenv(ENV_KEY);
        if (env != null && !env.trim().isEmpty()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(env.trim());
                if (decoded.length == 32) {
                    log.info("cryptoSupport init result 结果: keySource: env");
                    return new SecretKeySpec(decoded, "AES");
                }
                log.warn("cryptoSupport init result 结果: keySource: env-invalid-length, length: {}", decoded.length);
            } catch (IllegalArgumentException e) {
                log.warn("cryptoSupport init result 结果: keySource: env-invalid-base64, reason: {}", e.getMessage());
            }
        }
        // 缺省：机器绑定派生。生产建议显式提供 SQL_TUNER_DATA_KEY。
        byte[] derived = deriveMachineKey();
        log.warn("cryptoSupport init result 结果: keySource: machine-derived, hint: 生产环境请配置环境变量 {}", ENV_KEY);
        return new SecretKeySpec(derived, "AES");
    }

    private byte[] deriveMachineKey() {
        // 机器绑定因子：用户主目录 + 固定盐。换机器/换用户即无法解密。
        String factor = System.getProperty("user.home", "sql-tuner") + "|sql-tuner-state-key-v1";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(factor.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // SHA-256 一定存在；兜底用固定字节，至少不抛错阻断启动。
            byte[] fallback = new byte[32];
            for (int i = 0; i < fallback.length; i++) {
                fallback[i] = (byte) (factor.hashCode() >> (i % 4 * 8));
            }
            return fallback;
        }
    }

    /**
     * 加密明文为 "enc:v1:base64(iv+cipher)" 形式。
     * null/空串原样返回（不加密空值，避免把"未配置"变成"已加密空"）。
     * 已加密前缀的不重复加密（幂等）。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (isEncrypted(plaintext)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return "enc:v1:" + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 加密失败不应吞掉：落盘 key 是安全要求，失败要暴露。
            throw new IllegalStateException("加密敏感字段失败", e);
        }
    }

    /**
     * 解密 "enc:v1:..." 形式回明文。非加密前缀的值原样返回（兼容历史明文）。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !isEncrypted(stored)) {
            return stored;
        }
        try {
            String payload = stored.substring("enc:v1:".length());
            byte[] combined = Base64.getDecoder().decode(payload);
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherBytes = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, cipherBytes, 0, cipherBytes.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败：换机器/换 key 时可能发生。返回原值会让 LLM 拿到密文调用，必失败，
            // 但比抛错让服务起不来更可控（用户可在页面重新填 key）。
            log.warn("decrypt error 异常: 无法解密敏感字段，可能为换机器或更换密钥，reason: {}", e.getMessage());
            return null;
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith("enc:v1:");
    }
}
