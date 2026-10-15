import { useEffect, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import logo from '../../assets/images/grab-symbol.png'
import {
  mockSignupErrors,
  nicknameError,
  passwordError,
} from '../../features/auth/mock/mockAuth'

type Props = {
  notify: (message: string) => void
}

type CodeResult = 'success' | 'mismatch' | 'expired' | 'attempts'
type SaveResult = 'success' | 'existing' | 'nickname' | 'save' | 'context'

export default function SignupPage({ notify }: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  const provider =
    location.state?.provider === 'KAKAO' ||
    location.state?.provider === 'GOOGLE'
      ? (location.state.provider as 'KAKAO' | 'GOOGLE')
      : null
  const [step, setStep] = useState<'email' | 'code' | 'details'>('email')
  const [email, setEmail] = useState('')
  const [codeResult, setCodeResult] = useState<CodeResult>('success')
  const [saveResult, setSaveResult] = useState<SaveResult>('success')
  const [remaining, setRemaining] = useState(0)
  const [error, setError] = useState('')

  useEffect(() => {
    if (remaining <= 0) return
    const timer = window.setTimeout(() => setRemaining(remaining - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [remaining])

  const sendCode = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setStep('code')
    setRemaining(60)
    setError('')
  }

  const confirmCode = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (codeResult !== 'success') {
      setError(mockSignupErrors[codeResult])
      return
    }
    setStep('details')
    setError('')
  }

  const complete = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const nickname = String(form.get('nickname') ?? '').trim()
    const invalidNickname = nicknameError(nickname)
    if (invalidNickname) {
      setError(invalidNickname)
      return
    }
    if (!provider) {
      const invalidPassword = passwordError(String(form.get('password') ?? ''))
      if (invalidPassword) {
        setError(invalidPassword)
        return
      }
    }
    if (saveResult !== 'success') {
      setError(mockSignupErrors[saveResult])
      return
    }
    // 실제 가입 API는 인증 쿠키를 발급하지 않는다. 완료 후 로그인 화면으로 이동한다(M00-07).
    notify('회원가입이 완료됐어요. 로그인해주세요.')
    navigate('/login')
  }

  return (
    <section className="auth-page signup-page">
      <div className="auth-copy">
        <p className="eyebrow">WELCOME TO GRAB</p>
        <h1>취향을 발견할 준비가 됐나요?</h1>
        <p>이메일을 확인한 뒤 닉네임과 비밀번호를 정합니다.</p>
        <img src={logo} alt="" />
      </div>
      <div className="auth-form">
        <div className="auth-stack">
          <div className="auth-heading">
            <p className="eyebrow">START YOUR COLLECTION</p>
            <h2>{provider ? `${provider} 첫 가입` : 'LOCAL 회원가입'}</h2>
          </div>
          {!provider && (
            <ol className="auth-steps" aria-label="회원가입 단계">
              <li aria-current={step === 'email' ? 'step' : undefined}>
                1 이메일
              </li>
              <li aria-current={step === 'code' ? 'step' : undefined}>
                2 코드 확인
              </li>
              <li aria-current={step === 'details' ? 'step' : undefined}>
                3 계정 만들기
              </li>
            </ol>
          )}
          {!provider && step === 'email' && (
            <form className="auth-stack" onSubmit={sendCode}>
              <label>
                이메일 주소
                <input
                  required
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="grab@example.com"
                />
              </label>
              <button type="submit" className="primary-button full">
                인증 코드 보내기
              </button>
              <small>코드 발송 결과만으로 가입 여부는 알려주지 않습니다.</small>
            </form>
          )}
          {!provider && step === 'code' && (
            <form className="auth-stack" onSubmit={confirmCode}>
              <p>{email}로 보낸 코드 6자리를 입력해주세요.</p>
              <label>
                인증 코드 6자리
                <input
                  required
                  name="code"
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  autoComplete="one-time-code"
                />
              </label>
              <small>
                코드는 5분 유효하며 확인 횟수가 제한됩니다. 재발송은 60초 뒤에
                가능합니다.
              </small>
              <button
                type="button"
                className="secondary-button"
                disabled={remaining > 0}
                onClick={() => {
                  setRemaining(60)
                  setError('')
                }}
              >
                {remaining > 0
                  ? `${remaining}초 후 재발송`
                  : '인증 코드 재발송'}
              </button>
              <label className="demo-select">
                코드 확인 결과 예시
                <select
                  value={codeResult}
                  onChange={(event) => {
                    setCodeResult(event.target.value as CodeResult)
                    setError('')
                  }}
                >
                  <option value="success">확인 성공</option>
                  <option value="mismatch">코드 불일치</option>
                  <option value="expired">코드 만료</option>
                  <option value="attempts">시도 초과</option>
                </select>
              </label>
              {error && (
                <p className="form-error" role="alert">
                  {error}
                </p>
              )}
              <button type="submit" className="primary-button full">
                코드 확인
              </button>
            </form>
          )}
          {(provider || step === 'details') && (
            <form className="auth-stack" onSubmit={complete}>
              {provider ? (
                <p>{provider} 인증 후 닉네임을 정하는 데모 단계입니다.</p>
              ) : (
                <label>
                  인증된 이메일
                  <input readOnly value={email} />
                </label>
              )}
              <label>
                닉네임
                <input
                  required
                  name="nickname"
                  autoComplete="nickname"
                  placeholder="2~10자"
                />
              </label>
              {!provider && (
                <label>
                  비밀번호
                  <input
                    required
                    name="password"
                    type="password"
                    autoComplete="new-password"
                    placeholder="영문·숫자·특수문자 포함 8~64자"
                  />
                </label>
              )}
              <label className="demo-select">
                가입 결과 예시
                <select
                  value={saveResult}
                  onChange={(event) => {
                    setSaveResult(event.target.value as SaveResult)
                    setError('')
                  }}
                >
                  <option value="success">가입 성공</option>
                  {!provider && <option value="existing">기존 이메일</option>}
                  <option value="nickname">닉네임 중복</option>
                  <option value="save">저장 실패</option>
                  {provider && <option value="context">가입 시간 만료</option>}
                </select>
              </label>
              {error && (
                <p className="form-error" role="alert">
                  {error}
                </p>
              )}
              <button type="submit" className="primary-button full">
                회원가입 완료
              </button>
              {provider && (
                <small>
                  소셜 가입 정보는 이 화면에 표시하거나 URL에 담지 않습니다.
                </small>
              )}
            </form>
          )}
          <p>
            이미 계정이 있나요?{' '}
            <Link className="text-link" to="/login">
              로그인
            </Link>
          </p>
          <small>
            현재 화면은 Mock 데모입니다. 코드나 계정 정보는 저장하거나 전송하지
            않습니다.
          </small>
        </div>
      </div>
    </section>
  )
}
