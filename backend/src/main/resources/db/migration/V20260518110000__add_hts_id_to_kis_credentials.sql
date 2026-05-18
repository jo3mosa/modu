-- S14P31B106-291 — 체결통보 WS SUBSCRIBE 의 tr_key 로 HTS ID 가 필요해서 컬럼 추가.
-- AES-256-GCM 암호화 후 저장 (appKey/appSecret 와 동일 방식).
-- 기존 row 호환을 위해 nullable. 신규 등록 시 필수.
ALTER TABLE kis_credentials
    ADD COLUMN hts_id_enc VARCHAR(255);

COMMENT ON COLUMN kis_credentials.hts_id_enc IS 'KIS HTS 로그인 ID (AES-256-GCM 암호화). 체결통보 WS H0STCNI0 SUBSCRIBE 의 tr_key 로 사용';
