package com.modu.backend.domain.trading.execution.parser;

/**
 * KIS H0STCNI0 체결 통보 단건 — S14P31B106-291
 *
 * KIS 응답을 우리 시스템 의미 단위 (체결만) 로 추출. 본 PR 의 핵심 흐름인 체결 (CNTG_YN=2) 만 다루며
 * 주문/정정/취소/거부 (CNTG_YN=1) 통보는 ExecutionMessagePayloadParser 단에서 필터.
 *
 * [필드 매핑]
 *  custId      KIS CUST_ID         (HTS ID, 식별만)
 *  accountNo   KIS ACNT_NO         (10자 단축 형식)
 *  kisOrderNo  KIS ODER_NO         (orders.kis_order_no 매칭 키)
 *  side        KIS SELN_BYOV_CLS   (01=매도 / 02=매수)
 *  stockCode   KIS STCK_SHRN_ISCD  (orders.stock_code 매칭)
 *  cntgQty     KIS CNTG_QTY        (이번 체결 수량)
 *  cntgUnpr    KIS CNTG_UNPR       (체결 단가)
 *  cntgHHMMSS  KIS STCK_CNTG_HOUR  (HHmmss)
 */
public record ExecutionPayload(
        String custId,
        String accountNo,
        String kisOrderNo,
        ExecutionSide side,
        String stockCode,
        long   cntgQty,
        long   cntgUnpr,
        String cntgHHMMSS
) {

    public enum ExecutionSide {
        BUY, SELL;

        /** KIS SELN_BYOV_CLS — 01 매도, 02 매수 */
        public static ExecutionSide fromKisCode(String code) {
            return switch (code) {
                case "01" -> SELL;
                case "02" -> BUY;
                default -> throw new IllegalArgumentException("Unknown SELN_BYOV_CLS: " + code);
            };
        }
    }
}
