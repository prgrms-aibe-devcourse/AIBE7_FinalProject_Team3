-- 소셜 회원을 제공자 고유 사용자 식별자(OIDC sub)로 식별한다(GR-46 M00-01, ERD.md users, AUTH-006).
-- 이메일은 제공자 쪽에서 바뀌거나 재할당될 수 있어 재로그인 식별에 쓰지 않는다.
-- LOCAL 회원은 식별자가 없고, KAKAO·GOOGLE 회원은 필수다. 같은 제공자 안에서 식별자는 중복될 수 없다.
ALTER TABLE users ADD COLUMN provider_user_id VARCHAR(255);

ALTER TABLE users
    ADD CONSTRAINT uq_users_provider_user UNIQUE (provider, provider_user_id),
    ADD CONSTRAINT ck_users_social_identifier CHECK ((provider = 'LOCAL') = (provider_user_id IS NULL));
