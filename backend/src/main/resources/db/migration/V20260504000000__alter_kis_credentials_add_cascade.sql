-- kis_credentials: 사용자 삭제 시 KIS 자격 증명도 함께 삭제 (privacy/compliance)
ALTER TABLE kis_credentials DROP CONSTRAINT IF EXISTS FK_USERS_TO_KIS_CREDS;
ALTER TABLE kis_credentials
    ADD CONSTRAINT FK_USERS_TO_KIS_CREDS
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
