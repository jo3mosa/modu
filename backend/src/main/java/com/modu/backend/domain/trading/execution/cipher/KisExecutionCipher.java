package com.modu.backend.domain.trading.execution.cipher;

import com.modu.backend.global.error.ApiException;
import com.modu.backend.global.error.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * KIS 실시간 체결통보(H0STCNI0) 응답 복호화 유틸 — S14P31B106-291
 *
 * [암호화 방식]
 *  - AES-256 / CBC / PKCS#7 padding (Java 의 PKCS5Padding 이 AES 블록 16 byte 에서 PKCS#7 과 동일 동작)
 *  - IV / Key 는 KIS SUBSCRIBE 응답 body.output.iv / body.output.key 로 1회 수신
 *  - IV / Key 는 UTF-8 문자열로 받아 그대로 byte[] 로 사용 (KIS Python 공식 샘플 패턴)
 *  - 암호화 페이로드는 Base64 인코딩되어 전달됨
 *
 * [생애 주기]
 *  WebSocket 세션 단위로 1 instance. SUBSCRIBE 성공 응답 받은 시점에 IV/Key 보관. 재연결 시 새 instance.
 *
 * [실패 처리]
 *  복호화 실패는 EXTERNAL_API_ERROR 로 통일. 상위에서 메시지 무시 + ERROR 로그.
 */
@Slf4j
public class KisExecutionCipher {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    private final SecretKeySpec keySpec;
    private final IvParameterSpec ivSpec;

    public KisExecutionCipher(String key, String iv) {
        if (key == null || iv == null) {
            throw new IllegalArgumentException("KIS execution cipher key/iv must not be null");
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] ivBytes  = iv.getBytes(StandardCharsets.UTF_8);
        // AES-256 키 32 byte / CBC IV 16 byte. KIS 명세 위반 시 부팅 시점에 빠르게 실패시키도록 가드.
        if (keyBytes.length != 32 || ivBytes.length != 16) {
            throw new IllegalArgumentException(
                    "Unexpected KIS cipher size - keyBytes: " + keyBytes.length
                            + ", ivBytes: " + ivBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
        this.ivSpec  = new IvParameterSpec(ivBytes);
    }

    /**
     * Base64 인코딩된 KIS 체결통보 페이로드를 평문 UTF-8 문자열로 복호화.
     */
    public String decrypt(String base64Cipher) {
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(base64Cipher);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("KIS execution cipher decrypt failed", e);
            throw new ApiException(CommonErrorCode.EXTERNAL_API_ERROR, e);
        }
    }
}
