import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import logo from '../../assets/images/grab-symbol.png'
import {
  localAccount,
  mockLoginErrors,
  socialAccount,
  type MockAccount,
} from '../../features/auth/mockAuth'

type Props = {
  onLogin: (account: MockAccount) => void
  notify: (message: string) => void
}

type LoginResult = 'success' | 'credentials' | 'inactive' | 'network'
type SocialResult = 'existing' | 'new' | 'linked' | 'social'

export default function LoginPage({ onLogin, notify }: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const [loginResult, setLoginResult] = useState<LoginResult>('success')
  const [socialResult, setSocialResult] = useState<SocialResult>('existing')
  const [error, setError] = useState('')
  const returnTo =
    typeof location.state?.from === 'string' &&
    location.state.from.startsWith('/') &&
    !location.state.from.startsWith('//')
      ? location.state.from
      : '/wish'

  const completeLogin = (account: MockAccount) => {
    onLogin(account)
    notify('데모 로그인했어요. 브라우저를 다시 열면 로그인 상태가 사라집니다.')
    navigate(returnTo, { replace: true })
  }

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (loginResult !== 'success') {
      setError(mockLoginErrors[loginResult])
      return
    }
    completeLogin(localAccount)
  }

  const socialLogin = (provider: 'KAKAO' | 'GOOGLE') => {
    if (socialResult === 'new') {
      navigate('/signup', { state: { provider } })
    } else if (socialResult === 'existing') {
      completeLogin(socialAccount(provider))
    } else {
      setError(mockLoginErrors[socialResult])
    }
  }

  return (
    <section className="auth-page">
      <div className="auth-copy">
        <p className="eyebrow">WELCOME TO GRAB</p>
        <h1>다시 만나 반가워요.</h1>
        <p>좋아하는 상품을 WISH하고, 한정된 순간에 GRAB하세요.</p>
        <img src={logo} alt="" />
      </div>
      <div className="auth-form">
        <form className="auth-stack" onSubmit={submit}>
          <h2>로그인</h2>
          <label>
            이메일
            <input
              required
              type="email"
              autoComplete="email"
              placeholder="grab@example.com"
            />
          </label>
          <label>
            비밀번호
            <input
              required
              type="password"
              autoComplete="current-password"
              placeholder="비밀번호"
            />
          </label>
          <label className="demo-select">
            LOCAL 결과 예시
            <select
              value={loginResult}
              onChange={(event) => {
                setLoginResult(event.target.value as LoginResult)
                setError('')
              }}
            >
              <option value="success">로그인 성공</option>
              <option value="credentials">자격 증명 오류</option>
              <option value="inactive">비활성 계정</option>
              <option value="network">네트워크 오류</option>
            </select>
          </label>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <button type="submit" className="primary-button full">
            로그인
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
              onClick={() => socialLogin('KAKAO')}
            >
              KAKAO
            </button>
            <button
              type="button"
              className="secondary-button"
              onClick={() => socialLogin('GOOGLE')}
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
            현재 화면은 Mock 데모입니다. 입력한 정보는 저장하거나 전송하지
            않습니다.
          </small>
        </div>
      </div>
    </section>
  )
}
