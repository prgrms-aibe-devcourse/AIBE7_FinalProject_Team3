import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { nicknameError } from '../logic/signupValidation'
import { mockSignupErrors } from '../mock/mockAuth'

type Props = {
  notify: (message: string) => void
}

type SaveResult = 'success' | 'nickname' | 'save' | 'context'

// 소셜 최초 가입의 닉네임 단계. GR-47 연동 전까지 Mock이다.
// 콜백이 제공자를 전달하지 않으므로 제공자 이름은 표시하지 않는다(GR-31·GR-47 결정).
export default function SocialSignupForm({ notify }: Props) {
  const navigate = useNavigate()
  const [saveResult, setSaveResult] = useState<SaveResult>('success')
  const [error, setError] = useState('')

  const complete = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const nickname = String(form.get('nickname') ?? '').trim()
    const invalidNickname = nicknameError(nickname)
    if (invalidNickname) {
      setError(invalidNickname)
      return
    }
    if (saveResult !== 'success') {
      setError(mockSignupErrors[saveResult])
      return
    }
    notify('회원가입이 완료됐어요. 로그인해주세요.')
    navigate('/login')
  }

  return (
    <form className="auth-stack" onSubmit={complete}>
      <p>소셜 계정 인증 후 닉네임을 정하는 데모 단계입니다.</p>
      <label>
        닉네임
        <input
          required
          name="nickname"
          autoComplete="nickname"
          placeholder="2~10자"
        />
      </label>
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
          <option value="nickname">닉네임 중복</option>
          <option value="save">저장 실패</option>
          <option value="context">가입 시간 만료</option>
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
      <small>소셜 가입 정보는 이 화면에 표시하거나 URL에 담지 않습니다.</small>
      <small>
        현재 화면은 Mock 데모입니다. 코드나 계정 정보는 저장하거나 전송하지
        않습니다.
      </small>
    </form>
  )
}
