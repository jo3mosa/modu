package com.modu.backend.domain.trading.execution.parser;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * KIS H0STCNI0 체결 통보 메시지 파서 — S14P31B106-291
 *
 * [입력 형식 — KIS WebSocket 메시지]
 *   <암호화여부>|<TR_ID>|<데이터 개수>|<암호화 페이로드>
 *
 * [복호화 후 데이터 형식]
 *   <field1>^<field2>^...^<fieldN>  (단건)
 *   여러 건이면 동일 패턴이 데이터 개수만큼 반복
 *
 * [필드 인덱스 — KIS 명세 순서]
 *   0:CUST_ID 1:ACNT_NO 2:ODER_NO 3:OODER_NO 4:SELN_BYOV_CLS 5:RCTF_CLS 6:ODER_KIND
 *   7:ODER_COND 8:STCK_SHRN_ISCD 9:CNTG_QTY 10:CNTG_UNPR 11:STCK_CNTG_HOUR 12:RFUS_YN
 *   13:CNTG_YN ← 핵심 필터 (1=주문/정정/취소/거부 통보, 2=체결 통보)
 *   14:ACPT_YN 15:BRNC_NO 16:ODER_QTY 17:ACNT_NAME 18:ORD_COND_PRC 19:ORD_EXG_GB
 *   20:POPUP_YN 21:FILLER 22:CRDT_CLS 23:CRDT_LOAN_DATE 24:CNTG_ISNM40 25:ODER_PRC
 *
 * [필터링 정책]
 *   본 PR 은 CNTG_YN=2 (체결 통보) 만 변환. CNTG_YN=1 은 무시 (followups 후보).
 */
@Slf4j
public class ExecutionMessagePayloadParser {

    private static final char OUTER_DELIM = '|';
    private static final String INNER_DELIM = "\\^";

    /** 단건 레코드 필드 개수 — 정상 응답이면 최소 본 값 이상 (FILLER 부족 등 변형 대비 ODER_PRC 까지 26개) */
    private static final int MIN_FIELDS = 26;

    /** 필드 인덱스 상수 */
    private static final int IDX_CUST_ID        = 0;
    private static final int IDX_ACNT_NO        = 1;
    private static final int IDX_ODER_NO        = 2;
    private static final int IDX_SELN_BYOV_CLS  = 4;
    private static final int IDX_STCK_SHRN_ISCD = 8;
    private static final int IDX_CNTG_QTY       = 9;
    private static final int IDX_CNTG_UNPR      = 10;
    private static final int IDX_STCK_CNTG_HOUR = 11;
    private static final int IDX_CNTG_YN        = 13;

    /** CNTG_YN 값 — 체결 통보 */
    private static final String CNTG_YN_FILLED = "2";

    /**
     * 복호화된 페이로드 문자열 (`^` 구분, 다건 가능) → 체결 통보 단건 목록.
     *
     * 다건 응답: KIS 명세상 "데이터 개수" 가 들어오면 동일 패턴이 반복됨. 다만 외부 `|` 구분의
     * 3번째 필드가 데이터 개수를 표시하므로 본 파서는 외부 구분 제거된 평문만 다룬다 (호출자가 분할).
     *
     * 단건 record 가 데이터 개수만큼 이어붙어 들어올 수 있어 MIN_FIELDS 단위로 sliding window 분할.
     */
    public List<ExecutionPayload> parseFilledOnly(String decryptedPlaintext) {
        if (decryptedPlaintext == null || decryptedPlaintext.isBlank()) {
            return List.of();
        }
        String[] fields = decryptedPlaintext.split(INNER_DELIM, -1);
        if (fields.length < MIN_FIELDS) {
            log.warn("[ExecutionParser] 필드 수 부족 — 무시. fields: {}", fields.length);
            return List.of();
        }

        List<ExecutionPayload> result = new ArrayList<>();
        // sliding window — 정상이면 MIN_FIELDS 단위로 떨어져야 함
        for (int start = 0; start + MIN_FIELDS <= fields.length; start += MIN_FIELDS) {
            parseSingleRecord(fields, start).ifPresent(result::add);
        }
        return result;
    }

    /**
     * 외부 `|` 구분 제거 + 페이로드 추출 헬퍼.
     *
     * @return body part (인덱스 3) 의 텍스트. 형식 불일치 시 empty.
     */
    public Optional<String> extractDataPart(String rawMessage) {
        if (rawMessage == null) return Optional.empty();
        int first  = rawMessage.indexOf(OUTER_DELIM);
        int second = first  < 0 ? -1 : rawMessage.indexOf(OUTER_DELIM, first + 1);
        int third  = second < 0 ? -1 : rawMessage.indexOf(OUTER_DELIM, second + 1);
        if (third < 0) return Optional.empty();
        return Optional.of(rawMessage.substring(third + 1));
    }

    // ───────────────────────────────────────────────────────────────────
    // 내부 파싱
    // ───────────────────────────────────────────────────────────────────

    private Optional<ExecutionPayload> parseSingleRecord(String[] fields, int base) {
        String cntgYn = at(fields, base, IDX_CNTG_YN);
        if (!CNTG_YN_FILLED.equals(cntgYn)) {
            // CNTG_YN=1 (주문/정정/취소/거부) 통보는 본 PR 무시
            return Optional.empty();
        }
        try {
            return Optional.of(new ExecutionPayload(
                    at(fields, base, IDX_CUST_ID),
                    at(fields, base, IDX_ACNT_NO),
                    at(fields, base, IDX_ODER_NO),
                    ExecutionPayload.ExecutionSide.fromKisCode(at(fields, base, IDX_SELN_BYOV_CLS)),
                    at(fields, base, IDX_STCK_SHRN_ISCD),
                    parseLong(at(fields, base, IDX_CNTG_QTY)),
                    parseLong(at(fields, base, IDX_CNTG_UNPR)),
                    at(fields, base, IDX_STCK_CNTG_HOUR)
            ));
        } catch (IllegalArgumentException e) {
            log.warn("[ExecutionParser] 단건 파싱 실패 — 무시. base: {}, reason: {}", base, e.getMessage());
            return Optional.empty();
        }
    }

    /** null/blank 안전한 String index 접근 */
    private static String at(String[] fields, int base, int offset) {
        int idx = base + offset;
        if (idx >= fields.length) return "";
        String v = fields[idx];
        return v == null ? "" : v.trim();
    }

    /** KIS 응답 숫자 필드 — leading zero 포함된 형태. blank 는 0 으로 간주 */
    private static long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        return Long.parseLong(value);
    }
}
