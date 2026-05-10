-- orders: 동시 중복 주문 방지를 위한 (user_id, idempotency_key) 유니크 제약 추가
-- 선조회-실행 패턴의 race condition을 DB 레벨에서 방어
CREATE UNIQUE INDEX UQ_ORDERS_USER_IDEMPOTENCY ON orders (user_id, idempotency_key);
