import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import type { AuthUser } from '../../features/auth/logic/authUser'
import { takeSocialReturnPath } from '../../features/auth/logic/returnPath'
import {
  SOCIAL_LOGIN_INCOMPLETE_MESSAGE,
  socialCallbackErrorMessage,
} from '../../features/auth/logic/socialLoginErrors'

type Props = {
  refreshAuth: () => Promise<AuthUser | null>
  notify: (message: string) => void
}

// 소셜 로그인 콜백(MEMBER_AUTH 1.5). 서버가 result 또는 error 쿼리 하나만 붙여 이 화면으로 보낸다.
// 쿼리를 해석해 replace로 이동만 하므로 새로고침·뒤로 가기로 다시 처리되지 않는다(GR-47 M00-01).
export default function AuthCallbackPage({ refreshAuth, notify }: Props) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // StrictMode의 effect 재실행에서 이미 지운 복귀 경로를 다시 읽지 않도록 한 번만 처리한다(GR-47 M00-02)
  const handled = useRef(false)

  useEffect(() => {
    if (handled.current) return
    handled.current = true

    const result = searchParams.get('result')
    const returnTo = takeSocialReturnPath()
    // 복귀 경로는 위에서 이미 지웠으므로, 로그인 화면이 다시 시도할 때 저장하도록 from으로 넘긴다
    const fail = (message: string) =>
      navigate('/login', {
        replace: true,
        state: { socialError: message, from: returnTo },
      })

    if (result === 'signup_required') {
      navigate('/signup?type=social', { replace: true, state: { returnTo } })
      return
    }
    if (result === 'success') {
      void refreshAuth().then((user) => {
        if (!user) {
          fail(SOCIAL_LOGIN_INCOMPLETE_MESSAGE)
          return
        }
        notify('로그인했어요.')
        navigate(returnTo, { replace: true })
      })
      return
    }
    fail(socialCallbackErrorMessage(searchParams.get('error')))
  }, [navigate, notify, refreshAuth, searchParams])

  return (
    <section className="auth-page">
      <div className="auth-form">
        <p role="status">로그인 처리 중…</p>
      </div>
    </section>
  )
}
