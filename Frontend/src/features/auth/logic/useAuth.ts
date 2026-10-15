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

  const logout = async () => {
    await logoutRequest()
    await clearSessionCache(queryClient)
  }

  return {
    user,
    status,
    authenticated: status === 'authenticated',
    login,
    logout,
  }
}
