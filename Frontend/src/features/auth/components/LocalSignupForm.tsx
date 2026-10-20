import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  confirmEmailVerification,
  requestEmailVerification,
  signup,
} from '../../../api/auth/signup'
import {
  confirmCodeFailure,
  requestCodeErrorMessage,
  signupFailure,
  type SignupField,
  type SignupFieldErrors,
} from '../logic/signupErrors'
import { nicknameError, passwordError } from '../logic/signupValidation'

type Props = {
  notify: (message: string) => void
}

const FIELD_ORDER: SignupField[] = ['nickname', 'password']
const fieldErrorId = (field: SignupField) => `signup-${field}-error`

// LOCAL 회원가입 3단계(이메일 → 코드 확인 → 계정 만들기). MEMBER_AUTH 1.2.1~1.2.3을 순서대로 호출한다(GR-31).
export default function LocalSignupForm({ notify }: Props) {
  const navigate = useNavigate()
  const [step, setStep] = useState<'email' | 'code' | 'details'>('email')
  const [email, setEmail] = useState('')
  const [remaining, setRemaining] = useState(0)
  const [error, setError] = useState('')
  // 3단계 입력란 아래에 표시하는 오류(화면 사전 검증·서버 fieldErrors·닉네임 중복, GR-31 M00-03)
  const [fieldErrors, setFieldErrors] = useState<SignupFieldErrors>({})
  // 이미 가입된 이메일이면 오류 아래에 로그인 링크를 보여준다(GR-31 M00-03)
  const [showLoginLink, setShowLoginLink] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (remaining <= 0) return
    const timer = window.setTimeout(() => setRemaining(remaining - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [remaining])

  // 1단계(1.2.1). 204면 2단계로 가고 재발송 타이머를 시작한다(GR-31 M00-02).
  const sendCode = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setError('')
    try {
      await requestEmailVerification(email)
      setStep('code')
      setRemaining(60)
    } catch (requestError) {
      setError(requestCodeErrorMessage(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  // 2단계(1.2.2). 204면 서버가 가입 쿠키를 설정하고 3단계로 간다.
  const confirmCode = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return
    const code = String(new FormData(event.currentTarget).get('code') ?? '')
    setSubmitting(true)
    setError('')
    setShowLoginLink(false)
    try {
      await confirmEmailVerification(email, code)
      setStep('details')
    } catch (confirmError) {
      const failure = confirmCodeFailure(confirmError)
      setError(failure.message)
      setShowLoginLink(failure.kind === 'duplicateEmail')
    } finally {
      setSubmitting(false)
    }
  }

  // 2단계 재발송. 204일 때만 타이머를 다시 시작한다(GR-31 M00-02).
  const resendCode = async () => {
    if (submitting) return
    setSubmitting(true)
    setError('')
    setShowLoginLink(false)
    try {
      await requestEmailVerification(email)
      setRemaining(60)
    } catch (requestError) {
      setError(requestCodeErrorMessage(requestError))
    } finally {
      setSubmitting(false)
    }
  }

  // 이메일 오타 수정용. 입력한 이메일은 남기고 오류·타이머는 초기화한다(GR-31 M00-02).
  const editEmail = () => {
    setStep('email')
    setRemaining(0)
    setError('')
    setShowLoginLink(false)
  }

  // 3단계(1.2.3). 이메일은 가입 쿠키의 컨텍스트를 쓰므로 보내지 않는다.
  const complete = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return
    const formElement = event.currentTarget
    const form = new FormData(formElement)
    const nickname = String(form.get('nickname') ?? '').trim()
    const password = String(form.get('password') ?? '')
    const focusFirstInvalid = (errors: SignupFieldErrors) => {
      const field = FIELD_ORDER.find((name) => errors[name])
      const input = field && formElement.elements.namedItem(field)
      if (input instanceof HTMLInputElement) input.focus()
    }
    const invalid: SignupFieldErrors = {}
    const invalidNickname = nicknameError(nickname)
    if (invalidNickname) invalid.nickname = invalidNickname
    const invalidPassword = passwordError(password)
    if (invalidPassword) invalid.password = invalidPassword
    setError('')
    setShowLoginLink(false)
    setFieldErrors(invalid)
    if (Object.keys(invalid).length > 0) {
      focusFirstInvalid(invalid)
      return
    }
    setSubmitting(true)
    try {
      await signup(password, nickname)
      // 가입 API는 인증 쿠키를 발급하지 않는다. 완료 후 로그인 화면으로 이동한다(GR-45 M00-07).
      notify('회원가입이 완료됐어요. 로그인해주세요.')
      navigate('/login')
    } catch (signupError) {
      const failure = signupFailure(signupError)
      if (failure.kind === 'fields') {
        setFieldErrors(failure.fieldErrors)
        focusFirstInvalid(failure.fieldErrors)
      } else if (failure.kind === 'context') {
        // 가입 컨텍스트가 없거나 만료됐다. 이메일은 남기고 1단계로 돌아간다(GR-31 M00-02).
        setStep('email')
        setRemaining(0)
        setFieldErrors({})
        setError(failure.message)
      } else {
        setError(failure.message)
        setShowLoginLink(failure.kind === 'duplicateEmail')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <ol className="auth-steps" aria-label="회원가입 단계">
        <li aria-current={step === 'email' ? 'step' : undefined}>1 이메일</li>
        <li aria-current={step === 'code' ? 'step' : undefined}>2 코드 확인</li>
        <li aria-current={step === 'details' ? 'step' : undefined}>
          3 계정 만들기
        </li>
      </ol>
      {step === 'email' && (
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
            {submitting ? '보내는 중…' : '인증 코드 보내기'}
          </button>
          <small>코드 발송 결과만으로 가입 여부는 알려주지 않습니다.</small>
        </form>
      )}
      {step === 'code' && (
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
            disabled={remaining > 0 || submitting}
            onClick={resendCode}
          >
            {remaining > 0 ? `${remaining}초 후 재발송` : '인증 코드 재발송'}
          </button>
          <button
            type="button"
            className="secondary-button"
            disabled={submitting}
            onClick={editEmail}
          >
            이메일 다시 입력
          </button>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          {showLoginLink && (
            <Link className="text-link" to="/login">
              로그인하러 가기
            </Link>
          )}
          <button
            type="submit"
            className="primary-button full"
            disabled={submitting}
          >
            코드 확인
          </button>
        </form>
      )}
      {step === 'details' && (
        <form className="auth-stack" onSubmit={complete}>
          <label>
            인증된 이메일
            <input readOnly value={email} />
          </label>
          <label>
            닉네임
            <input
              required
              name="nickname"
              autoComplete="nickname"
              placeholder="2~10자"
              aria-invalid={fieldErrors.nickname ? true : undefined}
              aria-describedby={
                fieldErrors.nickname ? fieldErrorId('nickname') : undefined
              }
            />
          </label>
          {fieldErrors.nickname && (
            <p className="form-error" id={fieldErrorId('nickname')}>
              {fieldErrors.nickname}
            </p>
          )}
          <label>
            비밀번호
            <input
              required
              name="password"
              type="password"
              autoComplete="new-password"
              placeholder="영문·숫자·특수문자 포함 8~64자"
              aria-invalid={fieldErrors.password ? true : undefined}
              aria-describedby={
                fieldErrors.password ? fieldErrorId('password') : undefined
              }
            />
          </label>
          {fieldErrors.password && (
            <p className="form-error" id={fieldErrorId('password')}>
              {fieldErrors.password}
            </p>
          )}
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          {showLoginLink && (
            <Link className="text-link" to="/login">
              로그인하러 가기
            </Link>
          )}
          <button
            type="submit"
            className="primary-button full"
            disabled={submitting}
          >
            {submitting ? '가입하는 중…' : '회원가입 완료'}
          </button>
        </form>
      )}
    </>
  )
}
