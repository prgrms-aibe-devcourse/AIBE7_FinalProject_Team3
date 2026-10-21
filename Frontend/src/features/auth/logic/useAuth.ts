import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchMe,
  login as loginRequest,
  logout as logoutRequest,
} from '../../../api/auth/auth'
import { toAuthUser, type AuthStatus, type AuthUser } from './authUser'
import { authKeys } from './queryKeys'
import { clearSessionCache } from './clearSessionCache'

// 인증 상태는 users/me 쿼리 하나를 근거로 삼는다(M00-02). 새로고침하면 쿠키로 복구된다.
export default function useAuth() {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: authKeys.me(), queryFn: fetchMe })
  const user: AuthUser | null = query.data ? toAuthUser(query.data) : null
  const status: AuthStatus = query.isPending
    ? 'loading'
    : user
      ? 'authenticated'
      : 'anonymous'

  const login = async (email: string, password: string) => {
    await loginRequest(email, password)
    // 로그인 응답은 성공 확인에만 쓰고, 사용자 정보는 users/me로 다시 채운다(M00-02 A안).
    await queryClient.invalidateQueries({ queryKey: authKeys.me() })
  }

  // 소셜 콜백 success·소셜 가입 201 뒤 인증 쿠키로 users/me를 다시 조회한다(GR-47 M00-04).
  // invalidateQueries는 구독 중인 쿼리만 다시 조회하는데, 콜백 화면의 effect는 새로 로드된 앱에서
  // App의 useQuery 구독보다 먼저 실행되므로 fetchQuery로 직접 조회한다. 같은 키라 캐시도 함께 갱신된다.
  // 조회에 실패했거나 비로그인이면 null이다.
  const refresh = async (): Promise<AuthUser | null> => {
    try {
      const me = await queryClient.fetchQuery({
        queryKey: authKeys.me(),
        queryFn: fetchMe,
        staleTime: 0,
      })
      return me ? toAuthUser(me) : null
    } catch {
      return null
    }
  }

  const logout = async () => {
    await logoutRequest()
    await clearSessionCache(queryClient)
  }

  return {
    user,
    status,
    authenticated: status === 'authenticated',
    login,
    refresh,
    logout,
  }
}
