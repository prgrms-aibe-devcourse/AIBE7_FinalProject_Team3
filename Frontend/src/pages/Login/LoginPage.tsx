import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { socialLoginUrl, type SocialProvider } from '../../api/auth/oauth2'
import logo from '../../assets/images/grab-symbol.png'
import { loginErrorMessage } from '../../features/auth/logic/authUser'
import {
  safeReturnPath,
  saveSocialReturnPath,
} from '../../features/auth/logic/returnPath'

type Props = {
  login: (email: string, password: string) => Promise<void>
  notify: (message: string) => void
}

export default function LoginPage({ login, notify }: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  // 소셜 콜백·소셜 가입이 실패해 이 화면으로 보낸 안내(GR-47 M00-01·03)
  const [error, setError] = useState(() =>
    typeof location.state?.socialError === 'string'
      ? location.state.socialError
      : '',
  )
  const [submitting, setSubmitting] = useState(false)
  // 제공자 화면으로 떠나는 동안 소셜 버튼을 다시 누르지 못하게 한다
  const [redirecting, setRedirecting] = useState(false)
  // 상태는 다음 렌더에 반영되므로, 같은 작업 안에서 연달아 들어온 클릭은 ref로 막는다
  const redirectingRef = useRef(false)

  // 제공자 화면에서 뒤로 가기로 돌아오면 페이지가 bfcache에서 그대로 복원되므로 버튼을 다시 연다
  useEffect(() => {
    const reopen = (event: PageTransitionEvent) => {
      if (!event.persisted) return
      redirectingRef.current = false
      setRedirecting(false)
    }
    window.addEventListener('pageshow', reopen)
    return () => window.removeEventListener('pageshow', reopen)
  }, [])
  const returnTo = safeReturnPath(location.state?.from)

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setError('')
    try {
      await login(email, password)
      notify('로그인했어요.')
      navigate(returnTo, { replace: true })
    } catch (loginError) {
      setError(loginErrorMessage(loginError))
    } finally {
      setSubmitting(false)
    }
  }

  // 소셜 로그인은 API 호출이 아니라 페이지 이동이다(MEMBER_AUTH 1.4). 돌아오면 앱이 다시 로드돼
  // location.state가 사라지므로 복귀 경로를 sessionStorage에 남긴다(GR-47 M00-02).
  const socialLogin = (provider: SocialProvider) => {
    if (redirectingRef.current) return
    redirectingRef.current = true
    setRedirecting(true)
    setError('')
    saveSocialReturnPath(returnTo)
    window.location.assign(socialLoginUrl(provider))
  }

  return (
    <section className="auth-page login-page">
      <div className="auth-copy">
        <p className="eyebrow">WELCOME TO GRAB</p>
        <h1>다시 만나 반가워요.</h1>
        <p>좋아하는 상품을 WISH하고, 한정된 순간에 GRAB하세요.</p>
        <img src={logo} alt="" />
      </div>
      <div className="auth-form">
        <form className="auth-stack" onSubmit={submit}>
          <div className="auth-heading">
            <h2>로그인</h2>
          </div>
          <label>
            이메일
            <input
              required
              type="email"
              autoComplete="email"
              placeholder="grab@example.com"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label>
            비밀번호
            <input
              required
              type="password"
              autoComplete="current-password"
              placeholder="비밀번호"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <button
            type="submit"
            className="primary-button full"
            disabled={submitting}
          >
            {submitting ? '로그인 중…' : '로그인'}
          </button>
        </form>
        <div className="auth-stack auth-social">
          <p>또는 소셜 계정으로 계속</p>
          <div className="auth-actions">
            <button
              type="button"
              className="secondary-button"
              disabled={redirecting}
              onClick={() => socialLogin('kakao')}
            >
              KAKAO
            </button>
            <button
              type="button"
              className="secondary-button"
              disabled={redirecting}
              onClick={() => socialLogin('google')}
            >
              GOOGLE
            </button>
          </div>
          <p>
            아직 계정이 없나요?{' '}
            <Link className="text-link" to="/signup">
              회원가입
            </Link>
          </p>
        </div>
      </div>
    </section>
  )
}
