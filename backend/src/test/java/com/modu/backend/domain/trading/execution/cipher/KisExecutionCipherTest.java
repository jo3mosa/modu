package com.modu.backend.domain.trading.execution.cipher;

import com.modu.backend.global.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KIS AES-256 CBC 복호화 round-trip 검증 — S14P31B106-291
 *
 * KIS 가 보낼 형태와 동일하게 외부에서 암호화 → KisExecutionCipher 로 복호화 → 원문 일치 확인.
 */
class KisExecutionCipherTest {

    /** 32 byte UTF-8 KEY */
    private static final String KEY = "01234567890123456789012345678901";
    /** 16 byte UTF-8 IV */
    private static final String IV  = "0123456789012345";

    @Test
    @DisplayName("AES-256 CBC round-trip — 평문 복원")
    void roundTrip() throws Exception {
        String original = "HTSID^1234567801^0000002891^^02^0^00^0^005930^10^70000^094941^0^2^...";
        String encoded = encryptToBase64(original);

        KisExecutionCipher cipher = new KisExecutionCipher(KEY, IV);
        String decrypted = cipher.decrypt(encoded);

        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    @DisplayName("Key 길이 잘못 → IllegalArgumentException")
    void invalidKeyLength() {
        assertThatThrownBy(() -> new KisExecutionCipher("shortkey", IV))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IV 길이 잘못 → IllegalArgumentException")
    void invalidIvLength() {
        assertThatThrownBy(() -> new KisExecutionCipher(KEY, "shortiv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null Key/IV → IllegalArgumentException")
    void nullKeyOrIv() {
        assertThatThrownBy(() -> new KisExecutionCipher(null, IV))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KisExecutionCipher(KEY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된 Base64 입력 → ApiException(EXTERNAL_API_ERROR)")
    void invalidBase64() {
        KisExecutionCipher cipher = new KisExecutionCipher(KEY, IV);

        assertThatThrownBy(() -> cipher.decrypt("not-base64-!!!"))
                .isInstanceOf(ApiException.class);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────

    private static String encryptToBase64(String plaintext) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }
}
