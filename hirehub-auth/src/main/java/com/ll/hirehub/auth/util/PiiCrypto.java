package com.ll.hirehub.auth.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PII 字段加密（审计 S-10）：身份证号等敏感信息 AES-GCM 加密后落库。
 * <p>
 * 与 JWT 密钥同一套纪律：密钥由 {@code HIREHUB_PII_KEY} 注入（≥16 字节），
 * 仓库内置的开发默认值必须显式开启 {@code hirehub.security.allow-dev-defaults} 才可用。
 * <p>
 * 密文格式：Base64(12 字节随机 IV ‖ 密文+GCM 认证标签)。
 * ⚠️ 更换密钥前必须先全量解密再重加密（存量数据迁移），否则历史数据无法还原。
 */
@Slf4j
@Component
public class PiiCrypto {

    /** 仓库内置的开发默认密钥（仅本地开发，需 allow-dev-defaults=true） */
    static final String DEV_KEY = "hirehub-dev-pii-key-0123456789ab";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    @Value("${hirehub.security.pii-key:}")
    private String configuredKey;

    @Value("${hirehub.security.allow-dev-defaults:false}")
    private boolean allowDevDefaults;

    private SecretKeySpec key;

    /** 原始密钥材料（审计 F-11：HMAC 指纹需要 HmacSHA256 算法的密钥实例，不能与 AES 共用） */
    private byte[] keyMaterial;

    @PostConstruct
    void init() {
        String material = (configuredKey == null || configuredKey.isBlank()) ? DEV_KEY : configuredKey;
        if (DEV_KEY.equals(material)) {
            if (!allowDevDefaults) {
                throw new IllegalStateException(
                        "PII 加密密钥未注入（HIREHUB_PII_KEY），且未开启开发默认"
                                + "（hirehub.security.allow-dev-defaults=true），拒绝启动（审计 S-10）");
            }
            log.warn("正在使用开发默认 PII 加密密钥（allow-dev-defaults=true），仅限本地开发，严禁生产使用");
        }
        byte[] bytes = material.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 16) {
            throw new IllegalStateException("hirehub.security.pii-key 长度不足 16 字节（AES-128 最低要求）");
        }
        // 任意长度密钥材料取前 32 字节（AES-256），简单且对运维友好
        byte[] keyBytes = new byte[Math.min(bytes.length, 32)];
        System.arraycopy(bytes, 0, keyBytes, 0, keyBytes.length);
        this.key = new SecretKeySpec(keyBytes, "AES");
        this.keyMaterial = keyBytes;
    }

    /** 加密；入参为空原样返回（不浪费密文空间） */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("PII 加密失败", e);
        }
    }

    /** 解密（预留：后续需要展示/核对身份证的场景使用） */
    public String decrypt(String packed) {
        if (packed == null || packed.isEmpty()) {
            return packed;
        }
        try {
            byte[] all = Base64.getDecoder().decode(packed);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] ciphertext = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII 解密失败", e);
        }
    }

    /**
     * 确定性指纹（审计 F-11）：GCM 随机 IV 密文无法用于查重，
     * 用同一把密钥做 HMAC-SHA256 得到不可逆但确定性的指纹，支撑"一证一号"查重。
     * 指纹本身不可还原明文，即使泄露也无法逆推身份证号。
     */
    public String hash(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyMaterial, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("PII 指纹计算失败", e);
        }
    }
}
