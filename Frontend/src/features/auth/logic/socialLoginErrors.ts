import { NETWORK_MESSAGE } from './signupErrors'

// 소셜 로그인 콜백(MEMBER_AUTH 1.5)의 error 쿼리 → 로그인 화면 문구(GR-47 M00-03).
// 서버는 사용자 취소와 인증 실패를 구분하지 않고, 계정 연결이 필요한 기존 가입 방법도 알려주지 않는다.

// 알 수 없는 값·쿼리 없음, 그리고 success로 돌아왔지만 로그인 상태를 확인하지 못한 경우
export const SOCIAL_LOGIN_INCOMPLETE_MESSAGE =
  '소셜 로그인을 완료하지 못했어요. 다시 시도해주세요.'

const SOCIAL_CALLBACK_MESSAGES: Record<string, string> = {
  OAUTH2_AUTHENTICATION_FAILED:
    '소셜 로그인이 취소되었거나 실패했어요. 다시 시도해주세요.',
  OAUTH2_EMAIL_REQUIRED:
    '이메일 제공에 동의해야 가입할 수 있어요. 다시 시도해주세요.',
  ACCOUNT_LINK_REQUIRED:
    '이미 다른 방법으로 가입된 이메일이에요. 가입한 방법으로 로그인해주세요.',
  ACCOUNT_DISABLED: '사용할 수 없는 계정이에요.',
  INTERNAL_SERVER_ERROR: NETWORK_MESSAGE,
}

export function socialCallbackErrorMessage(code: string | null | undefined) {
  return code && Object.hasOwn(SOCIAL_CALLBACK_MESSAGES, code)
    ? SOCIAL_CALLBACK_MESSAGES[code]
    : SOCIAL_LOGIN_INCOMPLETE_MESSAGE
}
