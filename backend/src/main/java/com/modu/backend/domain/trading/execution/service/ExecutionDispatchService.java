package com.modu.backend.domain.trading.execution.service;

import com.modu.backend.domain.trading.execution.parser.ExecutionPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * KIS 체결 통보 단건 디스패치 — S14P31B106-291
 *
 * WebSocket 핸들러가 복호화 + 파싱 완료한 ExecutionPayload 를 받아 다음을 처리:
 *  1. ODER_NO 로 orders 찾기 (Pessimistic Lock)
 *  2. isFinalFill 계산 (filled_quantity + cntgQty ≥ quantity)
 *  3. TradeOrderProducer.publishOrderExecuted 호출 → PortfolioUpdateConsumer 가 DB/SSE/Redis 처리
 *
 * 현 시점 (A-4 작성) 은 stub. 실제 흐름은 A-7 에서 채움.
 */
@Slf4j
@Service
public class ExecutionDispatchService {

    /**
     * 체결 단건 디스패치 — WebSocket 핸들러가 호출.
     *
     * 본 메서드는 호출자 (WebSocket 핸들러) 스레드에서 실행되므로 무거운 작업은 비동기로 분리 필요할 수 있음.
     * 실제 구현은 A-7 에서 진행.
     */
    public void dispatch(ExecutionPayload payload) {
        // TODO(S14P31B106-291 A-7): orders 매칭 + isFinalFill + Kafka publish
        log.info("[ExecutionDispatch] (stub) received - kisOrderNo: {}, side: {}, qty: {}, price: {}",
                payload.kisOrderNo(), payload.side(), payload.cntgQty(), payload.cntgUnpr());
    }
}
