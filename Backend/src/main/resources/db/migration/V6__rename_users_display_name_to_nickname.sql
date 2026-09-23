-- 회원 표시 이름(display_name)을 닉네임으로 정의하고 컬럼명을 nickname으로 바꾼다.
-- 리뷰 등 공개 기능에서 다른 회원에게 노출되므로 닉네임 중복을 허용하지 않는다.
--
-- 중복 판단은 대소문자를 구분하지 않고(Grab = grab), 저장은 입력한 대소문자 그대로 한다.
-- UNIQUE 제약은 lower(...) 같은 식을 받지 않으므로 식 기반 유니크 인덱스를 사용한다.
-- 서버의 사전 중복 검사를 동시 요청이 함께 통과해도 이 인덱스가 최종 판단을 하며,
-- 위반(SQLState 23505, uq_users_nickname_lower)은 DUPLICATE_NICKNAME으로 변환한다.

ALTER TABLE users RENAME COLUMN display_name TO nickname;

CREATE UNIQUE INDEX uq_users_nickname_lower
    ON users (lower(nickname));
