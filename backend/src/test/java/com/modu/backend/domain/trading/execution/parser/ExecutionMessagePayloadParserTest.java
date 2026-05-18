package com.modu.backend.domain.trading.execution.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H0STCNI0 체결 통보 파서 검증 — S14P31B106-291
 *
 * KIS 명세 sample 페이로드 (CSV 첨부) 기반.
 */
class ExecutionMessagePayloadParserTest {

    private final ExecutionMessagePayloadParser parser = new ExecutionMessagePayloadParser();

    // 26 필드 단건 빌더. CNTG_YN 인덱스 13 만 인자로 받아 가변 처리
    private static String buildRecord(String cntgYn) {
        return buildRecord(cntgYn, "0000002891", "02", "005930", "10", "70000", "094941");
    }

    private static String buildRecord(String cntgYn, String oderNo, String sellBuy,
                                       String stockCode, String cntgQty, String cntgUnpr,
                                       String hhmmss) {
        // 26 필드 모두 채움 (위치 인덱스 — Parser 상수 참고)
        return String.join("^",
                "HTSID",          // 0  CUST_ID
                "1234567801",     // 1  ACNT_NO
                oderNo,           // 2  ODER_NO
                "",               // 3  OODER_NO
                sellBuy,          // 4  SELN_BYOV_CLS
                "0",              // 5  RCTF_CLS
                "00",             // 6  ODER_KIND
                "0",              // 7  ODER_COND
                stockCode,        // 8  STCK_SHRN_ISCD
                cntgQty,          // 9  CNTG_QTY
                cntgUnpr,         // 10 CNTG_UNPR
                hhmmss,           // 11 STCK_CNTG_HOUR
                "0",              // 12 RFUS_YN
                cntgYn,           // 13 CNTG_YN ← 필터 키
                "2",              // 14 ACPT_YN
                "06010",          // 15 BRNC_NO
                "10",             // 16 ODER_QTY
                "ACCT",           // 17 ACNT_NAME
                "0",              // 18 ORD_COND_PRC
                "1",              // 19 ORD_EXG_GB
                "Y",              // 20 POPUP_YN
                "",               // 21 FILLER
                "00",             // 22 CRDT_CLS
                "",               // 23 CRDT_LOAN_DATE
                "삼성전자",         // 24 CNTG_ISNM40
                "70000"           // 25 ODER_PRC
        );
    }

    @Test
    @DisplayName("CNTG_YN=2 (체결 통보) → 단건 파싱 성공")
    void filledRecordParsed() {
        String payload = buildRecord("2");

        List<ExecutionPayload> result = parser.parseFilledOnly(payload);

        assertThat(result).hasSize(1);
        ExecutionPayload p = result.get(0);
        assertThat(p.kisOrderNo()).isEqualTo("0000002891");
        assertThat(p.side()).isEqualTo(ExecutionPayload.ExecutionSide.BUY); // 02 = 매수
        assertThat(p.stockCode()).isEqualTo("005930");
        assertThat(p.cntgQty()).isEqualTo(10L);
        assertThat(p.cntgUnpr()).isEqualTo(70000L);
        assertThat(p.cntgHHMMSS()).isEqualTo("094941");
    }

    @Test
    @DisplayName("CNTG_YN=1 (주문/정정/취소/거부 통보) → 무시")
    void notifyRecordIgnored() {
        String payload = buildRecord("1");

        List<ExecutionPayload> result = parser.parseFilledOnly(payload);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("매도 (01) → ExecutionSide.SELL")
    void sellSideMapped() {
        String payload = buildRecord("2", "0000002892", "01",
                "005930", "5", "75000", "150000");

        List<ExecutionPayload> result = parser.parseFilledOnly(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).side()).isEqualTo(ExecutionPayload.ExecutionSide.SELL);
    }

    @Test
    @DisplayName("다건 페이로드 — 두 체결 단건 + 한 주문통보 → 체결 2건만 통과")
    void multipleRecordsSlidingWindow() {
        String payload = buildRecord("2") + "^"
                + buildRecord("1") + "^"
                + buildRecord("2", "0000002893", "01", "005930", "3", "71000", "100000");

        List<ExecutionPayload> result = parser.parseFilledOnly(payload);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).kisOrderNo()).isEqualTo("0000002891");
        assertThat(result.get(1).kisOrderNo()).isEqualTo("0000002893");
    }

    @Test
    @DisplayName("필드 개수 부족 → 빈 리스트")
    void tooFewFieldsIgnored() {
        String payload = "HTSID^1234567801^0000002891";

        List<ExecutionPayload> result = parser.parseFilledOnly(payload);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null/blank 입력 → 빈 리스트")
    void blankIgnored() {
        assertThat(parser.parseFilledOnly(null)).isEmpty();
        assertThat(parser.parseFilledOnly("")).isEmpty();
        assertThat(parser.parseFilledOnly("   ")).isEmpty();
    }

    @Test
    @DisplayName("extractDataPart — | 4분할 후 마지막 part 추출")
    void extractDataPart() {
        String raw = "1|H0STCNI0|001|HTSID^1234^FOO";

        assertThat(parser.extractDataPart(raw)).contains("HTSID^1234^FOO");
    }

    @Test
    @DisplayName("extractDataPart — | 3개 미만이면 empty")
    void extractDataPartMalformed() {
        assertThat(parser.extractDataPart("1|H0STCNI0|001")).isEmpty();
        assertThat(parser.extractDataPart(null)).isEmpty();
    }

    // ──────────────────────────────────────────────────────────
    // PR 리뷰 반영 — 공백 / 비숫자 CNTG_QTY/UNPR 폐기
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("CNTG_QTY 공백 → 레코드 폐기 (잘못된 0 체결 차단)")
    void blankCntgQtyDiscarded() {
        String payload = buildRecord("2", "0000002891", "02",
                "005930", "", "70000", "094941");

        assertThat(parser.parseFilledOnly(payload)).isEmpty();
    }

    @Test
    @DisplayName("CNTG_UNPR 공백 → 레코드 폐기 (잘못된 0 단가 차단)")
    void blankCntgUnprDiscarded() {
        String payload = buildRecord("2", "0000002891", "02",
                "005930", "10", "", "094941");

        assertThat(parser.parseFilledOnly(payload)).isEmpty();
    }

    @Test
    @DisplayName("CNTG_QTY 비숫자 → 레코드 폐기")
    void nonNumericCntgQtyDiscarded() {
        String payload = buildRecord("2", "0000002891", "02",
                "005930", "abc", "70000", "094941");

        assertThat(parser.parseFilledOnly(payload)).isEmpty();
    }

    @Test
    @DisplayName("정상 체결 + 공백 체결 혼합 → 정상만 통과")
    void mixedValidAndBlankRecords() {
        String payload = buildRecord("2") + "^"
                + buildRecord("2", "0000002893", "02", "005930", "", "70000", "094941");

        List<ExecutionPayload> result = parser.parseFilledOnly(payload);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).kisOrderNo()).isEqualTo("0000002891");
    }
}
