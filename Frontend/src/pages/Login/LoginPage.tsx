import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import logo from '../../assets/images/grab-symbol.png'
import { loginErrorMessage } from '../../features/auth/logic/authUser'
import { mockLoginErrors } from '../../features/auth/mock/mockAuth'

type Props = {
  login: (email: string, password: string) => Promise<void>
  notify: (message: string) => void
}

type SocialResult = 'existing' | 'new' | 'linked' | 'social'

export default function LoginPage({ login, notify }: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [socialResult, setSocialResult] = useState<SocialResult>('existing')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const returnTo =
    typeof location.state?.from === 'string' &&
    location.state.from.startsWith('/') &&
    !location.state.from.startsWith('//')
      ? location.state.from
      : '/wish'

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

  // 소셜 로그인은 GR-47에서 실제 리다이렉트로 연동한다. 지금은 결과 안내만 한다.
  // 가입 화면은 type 쿼리로 소셜 폼을 고르고 제공자 이름은 표시하지 않는다(GR-31 M00-01).
  const socialLogin = () => {
    if (socialResult === 'new') {
      navigate('/signup?type=social')
      return
    }
    if (socialResult === 'existing') {
      notify('소셜 로그인은 준비 중이에요. 곧 제공될 예정입니다.')
      return
    }
    setError(mockLoginErrors[socialResult])
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
          <label className="demo-select">
            소셜 결과 예시
            <select
              value={socialResult}
              onChange={(event) => {
                setSocialResult(event.target.value as SocialResult)
                setError('')
              }}
            >
              <option value="existing">기존 회원</option>
              <option value="new">첫 가입 · 닉네임 입력</option>
              <option value="linked">계정 연결 필요</option>
              <option value="social">취소 또는 인증 실패</option>
            </select>
          </label>
          <div className="auth-actions">
            <button
              type="button"
              className="secondary-button"
              onClick={socialLogin}
            >
              KAKAO
            </button>
            <button
              type="button"
              className="secondary-button"
              onClick={socialLogin}
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
          <small>
            LOCAL 로그인은 실제 인증 API를 사용합니다. 소셜 로그인은 준비 중이며
            소셜 결과 예시는 화면 안내용입니다.
          </small>
        </div>
      </div>
    </section>
  )
}
