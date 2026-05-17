-- S14P31B106-336 ReservedOrderRouting
--
-- trading_calendars 의 Y/N 컬럼 4개 타입 변경: CHAR(1) → VARCHAR(1)
--
-- 사유: JPA @Column(length=1) 가 VARCHAR(1) 로 매핑되어 schema-validation 단계에서 충돌 발생.
--       Postgres 의 bpchar (CHAR) 와 varchar(1) 사이 타입 불일치 ("found [bpchar], expecting [varchar(1)]").
-- 정책: 엔티티 코드를 그대로 두고 DB 타입을 맞춤. CHAR(1)/VARCHAR(1) 저장/성능 차이 무시할 수준.

ALTER TABLE trading_calendars
    ALTER COLUMN bzdy_yn     TYPE VARCHAR(1),
    ALTER COLUMN tr_day_yn   TYPE VARCHAR(1),
    ALTER COLUMN opnd_yn     TYPE VARCHAR(1),
    ALTER COLUMN sttl_day_yn TYPE VARCHAR(1);
