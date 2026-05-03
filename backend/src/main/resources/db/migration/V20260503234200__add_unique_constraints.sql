-- users 테이블: 동일 소셜 계정 중복 가입 방지 (provider + provider_id 복합 유니크)
ALTER TABLE users
    ADD CONSTRAINT UQ_USERS_PROVIDER UNIQUE (provider, provider_id);

-- refresh_tokens 테이블: 사용자당 하나의 활성 토큰만 허용 (user_id 유니크)
-- 기존 중복 데이터 정리 후 제약 추가
DELETE FROM refresh_tokens r1
    USING refresh_tokens r2
WHERE r1.id < r2.id
  AND r1.user_id = r2.user_id;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT UQ_REFRESH_TOKENS_USER_ID UNIQUE (user_id);
