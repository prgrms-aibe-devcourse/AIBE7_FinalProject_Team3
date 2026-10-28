import { useId, useRef, useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { changeMyPassword } from '../../../api/auth/me'
import {
  CURRENT_PASSWORD_REQUIRED_MESSAGE,
  NEW_PASSWORD_CONFIRM_MISMATCH_MESSAGE,
  NEW_PASSWORD_CONFIRM_REQUIRED_MESSAGE,
  PASSWORD_UNCHANGED_MESSAGE,
  passwordChangeFailure,
  type PasswordFormErrors,
  type PasswordFormField,
} from '../logic/accountErrors'
import { passwordError } from '../logic/signupValidation'

const FIELD_ORDER: PasswordFormField[] = [
  'currentPassword',
  'newPassword',
  'newPasswordConfirm',
]
const fieldErrorId = (field: PasswordFormField) => `account-${field}-error`

type Props = {
  notify: (message: string) => void
  onSuccess?: () => void
  onCancel?: () => void
}

type Passwords = Record<PasswordFormField, string>
const EMPTY: Passwords = {
  currentPassword: '',
  newPassword: '',
  newPasswordConfirm: '',
}

// LOCAL 회원 비밀번호 변경(MEMBER_AUTH 1.11, GR-131). 값은 이 컴포넌트 상태에만 두고(M00-04),
// 성공해도 세션이 유지되므로 재로그인 안내를 하지 않는다(M00-03).
// 새 비밀번호 확인은 오타 방지용으로 화면에서만 비교하고 서버에는 보내지 않는다(GR-160).
export default function PasswordChangeForm({
  notify,
  onSuccess,
  onCancel,
}: Props) {
  const inputRefs = useRef<
    Partial<Record<PasswordFormField, HTMLInputElement>>
  >({})
  const [values, setValues] = useState<Passwords>(EMPTY)
  const [fieldErrors, setFieldErrors] = useState<PasswordFormErrors>({})
  const [error, setError] = useState('')
  // 입력마다 따로 보이기를 켠다. 모달을 닫으면 폼과 함께 초기화된다.
  const [visible, setVisible] = useState<
    Partial<Record<PasswordFormField, boolean>>
  >({})
  const idPrefix = useId()
  const mutation = useMutation({
    mutationFn: ({ currentPassword, newPassword }: Passwords) =>
      changeMyPassword(currentPassword, newPassword),
  })

  const showFieldErrors = (errors: PasswordFormErrors) => {
    setFieldErrors(errors)
    const field = FIELD_ORDER.find((name) => errors[name])
    if (field) inputRefs.current[field]?.focus()
  }

  // 현재 비밀번호는 규칙을 검사하지 않는다. 규칙이 바뀌어도 기존 비밀번호로 변경할 수 있어야 한다(1.11).
  const validate = ({
    currentPassword,
    newPassword,
    newPasswordConfirm,
  }: Passwords) => {
    const errors: PasswordFormErrors = {}
    if (!currentPassword) {
      errors.currentPassword = CURRENT_PASSWORD_REQUIRED_MESSAGE
    }
    const invalidNew = passwordError(newPassword)
    if (invalidNew) errors.newPassword = invalidNew
    else if (newPassword === currentPassword) {
      errors.newPassword = PASSWORD_UNCHANGED_MESSAGE
    }
    if (!newPasswordConfirm) {
      errors.newPasswordConfirm = NEW_PASSWORD_CONFIRM_REQUIRED_MESSAGE
    } else if (newPasswordConfirm !== newPassword) {
      errors.newPasswordConfirm = NEW_PASSWORD_CONFIRM_MISMATCH_MESSAGE
    }
    return errors
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (mutation.isPending) return
    setError('')
    const invalid = validate(values)
    showFieldErrors(invalid)
    if (Object.keys(invalid).length > 0) return
    try {
      await mutation.mutateAsync(values)
      setValues(EMPTY)
      notify('비밀번호를 변경했어요.')
      onSuccess?.()
    } catch (changeError) {
      const failure = passwordChangeFailure(changeError)
      if (failure.kind === 'fields') {
        if (failure.resetCurrent) {
          setValues((current) => ({ ...current, currentPassword: '' }))
        }
        showFieldErrors(failure.fieldErrors)
      } else {
        setError(failure.message)
      }
    }
  }

  const change = (field: PasswordFormField, value: string) => {
    setValues((current) => ({ ...current, [field]: value }))
    setFieldErrors((current) => ({
      ...current,
      [field]: undefined,
      // 새 비밀번호를 고치면 확인 불일치 안내도 더는 맞지 않는다
      ...(field === 'newPassword' ? { newPasswordConfirm: undefined } : {}),
    }))
    setError('')
  }

  // 오류는 해당 입력 바로 아래에 둔다(모바일 한 열에서도 다른 입력 아래로 밀리지 않게).
  // 보기 버튼은 label 밖에 두어 입력의 이름이 바뀌지 않게 한다.
  const passwordInput = (
    field: PasswordFormField,
    label: string,
    autoComplete: string,
    placeholder?: string,
  ) => {
    const inputId = `${idPrefix}-${field}`
    const shown = visible[field] === true
    return (
      <div className="account-field">
        <label htmlFor={inputId}>{label}</label>
        <div className="password-input">
          <input
            id={inputId}
            ref={(element) => {
              if (element) inputRefs.current[field] = element
            }}
            required
            name={field}
            type={shown ? 'text' : 'password'}
            autoComplete={autoComplete}
            autoCapitalize="none"
            spellCheck={false}
            placeholder={placeholder}
            value={values[field]}
            onChange={(event) => change(field, event.target.value)}
            aria-invalid={fieldErrors[field] ? true : undefined}
            aria-describedby={
              fieldErrors[field] ? fieldErrorId(field) : undefined
            }
          />
          <button
            type="button"
            className="password-toggle"
            aria-label={`${label} ${shown ? '숨기기' : '보기'}`}
            aria-pressed={shown}
            onClick={() =>
              setVisible((current) => ({ ...current, [field]: !shown }))
            }
          >
            {shown ? <EyeOffIcon /> : <EyeIcon />}
          </button>
        </div>
        {fieldErrors[field] && (
          <p className="form-error" id={fieldErrorId(field)}>
            {fieldErrors[field]}
          </p>
        )}
      </div>
    )
  }

  return (
    <form
      className="account-edit"
      aria-label="비밀번호 변경"
      // 브라우저 기본 필수값 말풍선 대신 화면 사전 검증 문구·포커스로 안내한다
      noValidate
      onSubmit={(event) => void submit(event)}
    >
      {passwordInput('currentPassword', '현재 비밀번호', 'current-password')}
      {passwordInput(
        'newPassword',
        '새 비밀번호',
        'new-password',
        '영문·숫자·특수문자 포함 8~64자',
      )}
      {passwordInput('newPasswordConfirm', '새 비밀번호 확인', 'new-password')}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="account-edit-actions">
        {onCancel && (
          <button type="button" className="secondary-button" onClick={onCancel}>
            취소
          </button>
        )}
        <button
          type="submit"
          className="primary-button"
          disabled={mutation.isPending}
        >
          {mutation.isPending ? '변경 중…' : '비밀번호 변경'}
        </button>
      </div>
    </form>
  )
}

function EyeIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  )
}

function EyeOffIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M10.6 5.1A10.4 10.4 0 0 1 12 5c6.4 0 10 7 10 7a17.6 17.6 0 0 1-2.4 3.3M6.6 6.6C3.8 8.4 2 12 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2M2 2l20 20" />
    </svg>
  )
}
