import { api } from '../client'
import type { components, paths } from '../schema/user'

type EmailVerificationRequest =
  components['schemas']['EmailVerificationRequest']
type EmailVerificationConfirmRequest =
  components['schemas']['EmailVerificationConfirmRequest']
type SignupRequest = components['schemas']['SignupRequest']
type SignupResponse =
  paths['/api/v1/auth/signup']['post']['responses'][201]['content']['*/*']

export type SignedUpUser = components['schemas']['SignupResponse']

// LOCAL 회원가입 3단계(MEMBER_AUTH 1.2.1~1.2.3). CSRF·Credential은 공통 클라이언트가 처리하고,
// 가입 경로의 401은 재발급 대상에서 제외돼 있다(session.ts의 AUTH_PATHS).

// 1.2.1 코드 요청. 가입 여부와 관계없이 204다.
export async function requestEmailVerification(email: string) {
  const body: EmailVerificationRequest = { email }
  await api.post('/auth/email-verification', body)
}

// 1.2.2 코드 확인. 204면 서버가 email_signup_token HttpOnly 쿠키를 설정한다.
export async function confirmEmailVerification(email: string, code: string) {
  const body: EmailVerificationConfirmRequest = { email, code }
  await api.post('/auth/email-verification/confirm', body)
}

// 1.2.3 가입 완료. 이메일은 가입 쿠키의 컨텍스트를 쓰므로 보내지 않는다. 인증 쿠키는 발급되지 않는다.
export async function signup(password: string, nickname: string) {
  const body: SignupRequest = { password, nickname }
  const { data } = await api.post<SignupResponse>('/auth/signup', body)
  if (data.success !== true || !data.data) {
    throw new Error('회원가입 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
