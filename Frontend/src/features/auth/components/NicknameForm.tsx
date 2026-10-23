import { useRef, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateMyNickname } from '../../../api/auth/me'
import {
  NICKNAME_UNCHANGED_MESSAGE,
  nicknameUpdateFailure,
} from '../logic/accountErrors'
import type { AuthUser } from '../logic/authUser'
import { authKeys } from '../logic/queryKeys'
import { nicknameError } from '../logic/signupValidation'

const NICKNAME_ERROR_ID = 'account-nickname-error'

type Props = {
  user: AuthUser
  notify: (message: string) => void
}

// 마이페이지 닉네임 수정(MEMBER_AUTH 1.10, GR-131). 성공하면 응답으로 users/me 캐시를 바꿔
// 헤더 등 같은 캐시를 쓰는 화면도 함께 갱신한다(M00-03). 실패하면 표시 값은 그대로다.
export default function NicknameForm({ user, notify }: Props) {
  const queryClient = useQueryClient()
  const inputRef = useRef<HTMLInputElement>(null)
  const [nickname, setNickname] = useState(user.nickname)
  const [fieldError, setFieldError] = useState('')
  const [error, setError] = useState('')
  const mutation = useMutation({ mutationFn: updateMyNickname })

  const showFieldError = (message: string) => {
    setFieldError(message)
    inputRef.current?.focus()
  }

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (mutation.isPending) return
    setFieldError('')
    setError('')
    // 서버와 같은 규칙으로 먼저 막는다. 공백 제거 후 현재 값과 대소문자까지 같으면 변경이 아니다(M00-02).
    const invalid =
      nicknameError(nickname) ||
      (nickname.trim() === user.nickname ? NICKNAME_UNCHANGED_MESSAGE : '')
    if (invalid) {
      showFieldError(invalid)
      return
    }
    try {
      const me = await mutation.mutateAsync(nickname)
      queryClient.setQueryData(authKeys.me(), me)
      // 서버가 앞뒤 공백을 제거해 저장한 값으로 맞춘다
      setNickname(me.nickname ?? nickname.trim())
      notify('닉네임을 변경했어요.')
    } catch (saveError) {
      const failure = nicknameUpdateFailure(saveError)
      if (failure.kind === 'fields') {
        showFieldError(failure.fieldErrors.nickname)
      } else {
        setError(failure.message)
      }
    }
  }

  return (
    <form className="account-edit" onSubmit={(event) => void save(event)}>
      <label>
        이메일
        <input readOnly value={user.email} />
      </label>
      <label>
        닉네임 변경
        <input
          ref={inputRef}
          required
          name="nickname"
          autoComplete="nickname"
          value={nickname}
          onChange={(event) => {
            setNickname(event.target.value)
            setFieldError('')
            setError('')
          }}
          aria-invalid={fieldError ? true : undefined}
          aria-describedby={fieldError ? NICKNAME_ERROR_ID : undefined}
        />
      </label>
      {fieldError && (
        <p className="form-error" id={NICKNAME_ERROR_ID}>
          {fieldError}
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <button
        type="submit"
        className="primary-button"
        disabled={mutation.isPending}
      >
        {mutation.isPending ? '저장 중…' : '저장'}
      </button>
    </form>
  )
}
