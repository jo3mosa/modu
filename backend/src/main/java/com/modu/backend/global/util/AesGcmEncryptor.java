package com.modu.backend.global.util;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.modu.backend.global.config.EncryptionProperties;

/**
 * AES-256-GCM 암호화/복호화 유틸리티
 *
 * [저장 형식] Base64(IV) + ":" + Base64(암호문 + GCM 인증 태그)
 * [특징]
 * - IV는 암호화 시마다 랜덤 생성 → 동일 평문도 다른 암호문 생성
 * - GCM 인증 태그로 복호화 시 변조 여부 자동 검증
 */
@Component
public class AesGcmEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;  // 비트 단위
    private static final int IV_LENGTH = 12;         // GCM 권장 IV 길이 (바이트)

    private final SecretKey secretKey;

    public AesGcmEncryptor(EncryptionProperties properties) {
        // HEX 인코딩 키 사용 (Base64와 달리 특수문자 없어 .env 파싱 안전)
        String hexKey = properties.getKisKey().trim().toLowerCase();
        if (hexKey.length() != 64) {
            throw new IllegalStateException(
                    "KIS_ENCRYPTION_KEY는 정확히 64자리 HEX 문자열이어야 합니다 (현재: " + hexKey.length() + "자). " +
                    "터미널에서 'openssl rand -hex 32' 명령어로 생성해주세요."
            );
        }
        byte[] keyBytes = hexToBytes(hexKey);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 평문을 AES-256-GCM으로 암호화
     * 반환 형식: Base64(IV):Base64(암호문+인증태그)
     */
    public String encrypt(String plainText) {
        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plainText.getBytes());

            return Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(encrypted);

        } catch (Exception e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    /**
     * 암호문을 복호화해 평문 반환
     * GCM 인증 태그 검증 실패 시 예외 발생 (변조 감지)
     */
    public String decrypt(String encryptedText) {
        try {
            String[] parts = encryptedText.split(":");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return new String(cipher.doFinal(encrypted));

        } catch (Exception e) {
            throw new IllegalStateException("복호화 실패", e);
        }
    }

    private byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
