import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { socialSignup } from '../../../api/auth/oauth2'
import type { AuthUser } from '../logic/authUser'
import { safeReturnPath } from '../logic/returnPath'
import {
  SOCIAL_SIGNUP_LOGIN_REQUIRED_MESSAGE,
  socialSignupFailure,
} from '../logic/signupErrors'
import { nicknameError } from '../logic/signupValidation'

type Props = {
  refreshAuth: () => Promise<AuthUser | null>
  notify: (message: string) => void
}

const NICKNAME_ERROR_ID = 'social-signup-nickname-error'

// 소셜 최초 가입의 닉네임 단계(MEMBER_AUTH 1.6). 제공자·이메일은 HttpOnly 가입 쿠키의 컨텍스트를 쓰므로 닉네임만 보낸다.
// 콜백이 제공자를 전달하지 않으므로 제공자 이름은 표시하지 않는다(GR-31·GR-47 결정).
export default function SocialSignupForm({ refreshAuth, notify }: Props) {
  const navigate = useNavigate()
  const location = useLocation()
  // 콜백이 넘긴 복귀 경로. 새로고침하면 사라져 기본 경로로 간다(GR-47 M00-02).
  const returnTo = safeReturnPath(location.state?.returnTo)
  const [nicknameMessage, setNicknameMessage] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const complete = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting) return
    const formElement = event.currentTarget
    const nickname = String(
      new FormData(formElement).get('nickname') ?? '',
    ).trim()
    const focusNickname = () => {
      const input = formElement.elements.namedItem('nickname')
      if (input instanceof HTMLInputElement) input.focus()
    }
    setError('')
    const invalidNickname = nicknameError(nickname)
    setNicknameMessage(invalidNickname)
    if (invalidNickname) {
      focusNickname()
      return
    }
    setSubmitting(true)
    try {
      await socialSignup(nickname)
      // 201이면 인증 쿠키가 설정됐다. users/me로 로그인 상태를 복구하고 이 화면을 다시 제출할 수 없게 replace로 떠난다(GR-47 M00-04).
      const user = await refreshAuth()
      if (!user) {
        // 계정은 만들어졌지만 로그인 상태를 확인하지 못했다. 소셜 로그인을 다시 하면 기존 회원으로 로그인된다.
        // 로그인 화면이 다음 소셜 시작에 같은 복귀 경로를 저장하도록 from으로 넘긴다.
        navigate('/login', {
          replace: true,
          state: {
            socialError: SOCIAL_SIGNUP_LOGIN_REQUIRED_MESSAGE,
            from: returnTo,
          },
        })
        return
      }
      notify('회원가입이 완료됐어요.')
      navigate(returnTo, { replace: true })
    } catch (signupError) {
      const failure = socialSignupFailure(signupError)
      if (failure.kind === 'fields') {
        setNicknameMessage(failure.fieldErrors.nickname)
        focusNickname()
      } else if (failure.kind === 'restart') {
        // 컨텍스트가 무효이거나 소비돼 같은 화면에서 다시 보낼 수 없다. 로그인 화면에서 소셜 로그인부터 다시 시작한다(GR-47 M00-03).
        navigate('/login', {
          replace: true,
          state: { socialError: failure.message, from: returnTo },
        })
      } else {
        setError(failure.message)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="auth-stack" onSubmit={complete}>
      <label>
        닉네임
        <input
          required
          name="nickname"
          autoComplete="nickname"
          placeholder="2~10자"
          aria-invalid={nicknameMessage ? true : undefined}
          aria-describedby={nicknameMessage ? NICKNAME_ERROR_ID : undefined}
        />
      </label>
      {nicknameMessage && (
        <p className="form-error" id={NICKNAME_ERROR_ID}>
          {nicknameMessage}
        </p>
      )}
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
        {submitting ? '가입하는 중…' : '회원가입 완료'}
      </button>
      <small>소셜 가입 정보는 이 화면에 표시하거나 URL에 담지 않습니다.</small>
    </form>
  )
}
