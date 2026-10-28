import axios from 'axios'
import type { UserMe } from '../../../api/auth/auth'

export type AuthProvider = 'LOCAL' | 'KAKAO' | 'GOOGLE'
export type AuthRole = 'USER' | 'SELLER' | 'ADMIN'
export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

// API는 profileImageUrl을 null로 줄 수 있는데 명세 타입은 string이라 느슨하게 받는다.
export type AuthUserSource = Omit<UserMe, 'profileImageUrl'> & {
  profileImageUrl?: string | null
}

// 화면용 회원 모델. API 응답(users/me)을 변환한 값이며 두 필드(provider·profileImageUrl)는 필수로 둔다.
export type AuthUser = {
  userId: string
  email: string
  nickname: string
  provider: AuthProvider
  roles: string[]
  profileImageUrl: string | null
  createdAt: string
}

export function toAuthUser(raw: AuthUserSource): AuthUser {
  return {
    userId: raw.userId ?? '',
    email: raw.email ?? '',
    nickname: raw.nickname ?? '',
    provider: raw.provider ?? 'LOCAL',
    roles: raw.roles ?? ['USER'],
    profileImageUrl: raw.profileImageUrl ?? null,
    createdAt: raw.createdAt ?? '',
  }
}

export function hasRole(user: AuthUser | null, role: AuthRole) {
  return user?.roles.includes(role) ?? false
}

export function isAdmin(user: AuthUser | null) {
  return hasRole(user, 'ADMIN')
}

export function isSeller(user: AuthUser | null) {
  return hasRole(user, 'SELLER')
}

type ErrorBody = { error?: { code?: string } }

// 비밀번호 해시 동시 처리 상한 초과(503, GR-178). 로그인·회원가입·비밀번호 변경이 함께 쓴다.
// 일시적 거부라 연결 실패와 구분해 안내하고, 폭주를 키우지 않도록 자동으로 다시 보내지 않는다.
export const AUTH_TEMPORARILY_UNAVAILABLE_MESSAGE =
  '접속이 많아요. 잠시 뒤 다시 시도해주세요.'

export function apiErrorCode(error: unknown) {
  if (!axios.isAxiosError(error)) return undefined
  return (error.response?.data as ErrorBody | undefined)?.error?.code
}

export function loginErrorMessage(error: unknown) {
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  const code = apiErrorCode(error)
  if (code === 'INVALID_CREDENTIALS') {
    return '이메일 또는 비밀번호를 확인해주세요.'
  }
  if (code === 'ACCOUNT_DISABLED') return '사용할 수 없는 계정이에요.'
  if (code === 'AUTH_TEMPORARILY_UNAVAILABLE') {
    return AUTH_TEMPORARILY_UNAVAILABLE_MESSAGE
  }
  if (code === 'VALIDATION_FAILED' || code === 'INVALID_REQUEST') {
    return '이메일과 비밀번호 형식을 확인해주세요.'
  }
  if (status === undefined) {
    return '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'
  }
  if (status >= 500) {
    return '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'
  }
  return '로그인하지 못했어요. 잠시 뒤 다시 시도해주세요.'
}
