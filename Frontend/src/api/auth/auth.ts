import axios from 'axios'
import { api } from '../client'
import type { components, paths } from '../schema/user'
import {
  beginLogout,
  endLogout,
  refreshSession,
  waitForActiveRefresh,
} from './session'

type LoginResponse =
  paths['/api/v1/auth/login']['post']['responses'][200]['content']['*/*']
type MeResponse =
  paths['/api/v1/users/me']['get']['responses'][200]['content']['*/*']

export type UserMe = components['schemas']['UserMeResponse']
export type LoginUser = components['schemas']['UserInfo']

// 성공 응답의 data만 반환한다. 응답 본문·로그에 쿠키 값을 남기지 않는다(README 규칙).
export async function login(email: string, password: string) {
  const { data } = await api.post<LoginResponse>('/auth/login', {
    email,
    password,
  })
  if (data.success !== true || !data.data?.user) {
    throw new Error('로그인 응답 형식이 올바르지 않습니다.')
  }
  return data.data.user
}

export function refresh() {
  return refreshSession()
}

export async function fetchMe(): Promise<UserMe | null> {
  try {
    const { data } = await api.get<MeResponse>('/users/me')
    if (data.success !== true || !data.data) {
      throw new Error('내 정보 응답 형식이 올바르지 않습니다.')
    }
    return data.data
  } catch (error) {
    // 401은 비로그인 상태다. 재발급까지 실패하면 세션 만료로 정리하고 여기서 null로 돌린다.
    if (axios.isAxiosError(error) && error.response?.status === 401) return null
    throw error
  }
}

export async function logout() {
  beginLogout()
  try {
    // 진행 중인 재발급이 Set-Cookie로 인증 쿠키를 되살리지 않도록 끝난 뒤에 보낸다.
    await waitForActiveRefresh()
    await api.post('/auth/logout')
  } finally {
    endLogout()
  }
}
