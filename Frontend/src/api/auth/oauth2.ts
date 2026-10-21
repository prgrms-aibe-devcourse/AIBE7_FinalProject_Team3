import { api } from '../client'
import type { components, paths } from '../schema/user'

type OAuth2SignupRequest = components['schemas']['OAuth2SignupRequest']
type OAuth2SignupResponse =
  paths['/api/v1/auth/oauth2/signup']['post']['responses'][201]['content']['*/*']

export type SocialProvider = 'kakao' | 'google'

// KAKAO·GOOGLE 소셜 로그인(MEMBER_AUTH 1.4~1.6). 결과는 프론트엔드 콜백 주소의 result·error 쿼리로 돌아온다.

// 1.4 시작. Axios로 부르면 302를 따라가 제공자 HTML만 받으므로 브라우저를 이 주소로 이동시킨다.
export function socialLoginUrl(provider: SocialProvider) {
  return `${api.defaults.baseURL}/auth/oauth2/${provider}`
}

// 1.6 가입 완료. 제공자·이메일은 oauth2_signup_token 쿠키의 컨텍스트를 쓰므로 닉네임만 보낸다.
// 201이면 인증 쿠키가 설정된다. 이 경로의 401은 재발급 대상에서 제외돼 있다(session.ts의 AUTH_PATHS).
export async function socialSignup(nickname: string) {
  const body: OAuth2SignupRequest = { nickname }
  const { data } = await api.post<OAuth2SignupResponse>(
    '/auth/oauth2/signup',
    body,
  )
  if (data.success !== true || !data.data?.user) {
    throw new Error('소셜 회원가입 응답 형식이 올바르지 않습니다.')
  }
  return data.data.user
}
