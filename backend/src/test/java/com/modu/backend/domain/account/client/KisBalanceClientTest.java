package com.modu.backend.domain.account.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * KisBalanceClient 헬퍼 메서드 단위 테스트 (S14P31B106-360).
 *
 * 핵심 회귀 방지:
 *  - pchs_avg_pric (매입평균가) 가 분할 매수 시 소수점 응답 ('2436.6000') 으로 와도
 *    parseDouble 가 정상 처리해야 함
 *  - KIS 응답 명세상 모든 필드가 String — BE 측 number 타입 매핑 책임
 */
class KisBalanceClientTest {

    private final KisBalanceClient client = new KisBalanceClient(mock(RestClient.class));

    @Test
    @DisplayName("parseDouble — 소수점 응답 ('2436.6000') 도 정상 파싱 + 둘째자리 반올림")
    void parseDouble_decimalResponse() {
        double result = invokeParseDouble("2436.6000");

        assertThat(result).isCloseTo(2436.60, within(0.01));
    }

    @Test
    @DisplayName("parseDouble — 정수 응답도 그대로 파싱")
    void parseDouble_integerResponse() {
        assertThat(invokeParseDouble("75000")).isEqualTo(75000.0);
    }

    @Test
    @DisplayName("parseDouble — null / blank 은 0.0")
    void parseDouble_nullOrBlank() {
        assertThat(invokeParseDouble(null)).isEqualTo(0.0);
        assertThat(invokeParseDouble("")).isEqualTo(0.0);
        assertThat(invokeParseDouble("   ")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("parseDouble — 형식 오류는 ERROR 로그 후 0.0 (전체 응답 파싱 중단 방지)")
    void parseDouble_invalidFormat() {
        assertThat(invokeParseDouble("abc")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("parseLong — 정수 응답은 정상 파싱")
    void parseLong_integerResponse() {
        assertThat(invokeParseLong("10")).isEqualTo(10L);
    }

    @Test
    @DisplayName("parseLong — null / blank / 형식 오류는 0 (소수점도 폴백)")
    void parseLong_fallback() {
        assertThat(invokeParseLong(null)).isZero();
        assertThat(invokeParseLong("")).isZero();
        // pchs_avg_pric 같은 소수점 응답은 parseDouble 로 처리해야 함 — parseLong 폴백 검증
        assertThat(invokeParseLong("2436.6000")).isZero();
    }

    private double invokeParseDouble(String value) {
        Object result = ReflectionTestUtils.invokeMethod(client, "parseDouble", value);
        return result == null ? 0.0 : (double) result;
    }

    private long invokeParseLong(String value) {
        Object result = ReflectionTestUtils.invokeMethod(client, "parseLong", value);
        return result == null ? 0L : (long) result;
    }
}
