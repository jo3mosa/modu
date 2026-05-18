-- S14P31B106-291 ExecutionNotification
--
-- order_executions (order_id, kis_execution_no) UNIQUE 제약 추가
--
-- 사유: KIS H0STCNI0 가 동일 체결을 중복 통보할 수 있음 (네트워크 재시도 / WebSocket reconnect).
--      PortfolioUpdateConsumer 가 멱등성 보장 위해 INSERT 시 UNIQUE 위반 catch 로 silent skip.
-- 스코프: (order_id, kis_execution_no) — KIS 체결번호가 주문별 식별이라는 가정.
--        전역 UNIQUE 보다 좁게 (다른 주문이 동일 번호 가질 수 있는 case 대비).

ALTER TABLE order_executions
    ADD CONSTRAINT uq_order_executions_order_kis_exec
    UNIQUE (order_id, kis_execution_no);
