import { useRef, useState, type FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { changeMyPassword } from '../../../api/auth/me'
import {
  CURRENT_PASSWORD_REQUIRED_MESSAGE,
  PASSWORD_UNCHANGED_MESSAGE,
  passwordChangeFailure,
  type PasswordField,
  type PasswordFieldErrors,
} from '../logic/accountErrors'
import { passwordError } from '../logic/signupValidation'

const FIELD_ORDER: PasswordField[] = ['currentPassword', 'newPassword']
const fieldErrorId = (field: PasswordField) => `account-${field}-error`

type Props = {
  notify: (message: string) => void
}

type Passwords = { currentPassword: string; newPassword: string }
const EMPTY: Passwords = { currentPassword: '', newPassword: '' }

// LOCAL 회원 비밀번호 변경(MEMBER_AUTH 1.11, GR-131). 값은 이 컴포넌트 상태에만 두고(M00-04),
// 성공해도 세션이 유지되므로 재로그인 안내를 하지 않는다(M00-03).
export default function PasswordChangeForm({ notify }: Props) {
  const inputRefs = useRef<Partial<Record<PasswordField, HTMLInputElement>>>({})
  const [values, setValues] = useState<Passwords>(EMPTY)
  const [fieldErrors, setFieldErrors] = useState<PasswordFieldErrors>({})
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: ({ currentPassword, newPassword }: Passwords) =>
      changeMyPassword(currentPassword, newPassword),
  })

  const showFieldErrors = (errors: PasswordFieldErrors) => {
    setFieldErrors(errors)
    const field = FIELD_ORDER.find((name) => errors[name])
    if (field) inputRefs.current[field]?.focus()
  }

  // 현재 비밀번호는 규칙을 검사하지 않는다. 규칙이 바뀌어도 기존 비밀번호로 변경할 수 있어야 한다(1.11).
  const validate = ({ currentPassword, newPassword }: Passwords) => {
    const errors: PasswordFieldErrors = {}
    if (!currentPassword) {
      errors.currentPassword = CURRENT_PASSWORD_REQUIRED_MESSAGE
    }
    const invalidNew = passwordError(newPassword)
    if (invalidNew) errors.newPassword = invalidNew
    else if (newPassword === currentPassword) {
      errors.newPassword = PASSWORD_UNCHANGED_MESSAGE
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

  const passwordInput = (
    field: PasswordField,
    label: string,
    autoComplete: string,
    placeholder?: string,
  ) => (
    <label>
      {label}
      <input
        ref={(element) => {
          if (element) inputRefs.current[field] = element
        }}
        required
        name={field}
        type="password"
        autoComplete={autoComplete}
        placeholder={placeholder}
        value={values[field]}
        onChange={(event) => {
          setValues((current) => ({
            ...current,
            [field]: event.target.value,
          }))
          setFieldErrors((current) => ({ ...current, [field]: undefined }))
          setError('')
        }}
        aria-invalid={fieldErrors[field] ? true : undefined}
        aria-describedby={fieldErrors[field] ? fieldErrorId(field) : undefined}
      />
    </label>
  )

  return (
    <form
      className="account-edit"
      aria-label="비밀번호 변경"
      onSubmit={(event) => void submit(event)}
    >
      {passwordInput('currentPassword', '현재 비밀번호', 'current-password')}
      {passwordInput(
        'newPassword',
        '새 비밀번호',
        'new-password',
        '영문·숫자·특수문자 포함 8~64자',
      )}
      {FIELD_ORDER.map(
        (field) =>
          fieldErrors[field] && (
            <p className="form-error" id={fieldErrorId(field)} key={field}>
              {fieldErrors[field]}
            </p>
          ),
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
        {mutation.isPending ? '변경 중…' : '비밀번호 변경'}
      </button>
    </form>
  )
}
