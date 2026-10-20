import axios from 'axios'
import { apiErrorCode } from './authUser'
import {
  NICKNAME_RULE_MESSAGE,
  PASSWORD_RULE_MESSAGE,
} from './signupValidation'

// LOCAL 회원가입 단계별 오류 → 화면 처리(GR-31 M00-03). 코드와 field로 분기하고 서버의 reason 문구는 쓰지 않는다.

export type SignupField = 'nickname' | 'password'
export type SignupFieldErrors = Partial<Record<SignupField, string>>

// 3단계 실패: 입력란 아래에 표시(fields), 1단계로 돌아감(context), 로그인 안내(duplicateEmail), 폼 하단 표시(general)
export type SignupFailure =
  | { kind: 'fields'; fieldErrors: SignupFieldErrors }
  | { kind: 'context'; message: string }
  | { kind: 'duplicateEmail'; message: string }
  | { kind: 'general'; message: string }

const NETWORK_MESSAGE = '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'
const GENERAL_MESSAGE = '요청을 처리하지 못했어요. 다시 시도해주세요.'
const EMAIL_FORMAT_MESSAGE = '이메일 주소를 확인해주세요.'
const RESEND_TOO_SOON_MESSAGE =
  '인증 코드를 너무 자주 요청했어요. 잠시 뒤 다시 요청해주세요.'
export const DUPLICATE_EMAIL_MESSAGE =
  '이미 가입된 이메일이에요. 로그인해주세요.'
export const SIGNUP_CONTEXT_MESSAGE =
  '가입 시간이 지났어요. 인증 코드를 다시 받아주세요.'

const FIELD_RULE_MESSAGES: Record<SignupField, string> = {
  nickname: NICKNAME_RULE_MESSAGE,
  password: PASSWORD_RULE_MESSAGE,
}

type FieldErrorBody = { error?: { fieldErrors?: { field?: string }[] } }

// 네트워크 오류·5xx는 연결 실패, 그 밖의 처리하지 못한 응답(INVALID_REQUEST, CSRF 재시도 후 403 등)은 일반 실패다.
function fallbackMessage(error: unknown) {
  if (!axios.isAxiosError(error)) return GENERAL_MESSAGE
  const status = error.response?.status
  if (status === undefined || status >= 500) return NETWORK_MESSAGE
  return GENERAL_MESSAGE
}

// 1단계(1.2.1) 코드 요청, 2단계 재발송
export function requestCodeErrorMessage(error: unknown) {
  const code = apiErrorCode(error)
  if (code === 'INVALID_EMAIL' || code === 'VALIDATION_FAILED') {
    return EMAIL_FORMAT_MESSAGE
  }
  if (code === 'EMAIL_VERIFICATION_RESEND_TOO_SOON') {
    return RESEND_TOO_SOON_MESSAGE
  }
  return fallbackMessage(error)
}

// 2단계 코드 확인 실패: 로그인 안내(duplicateEmail), 같은 단계에서 다시 입력·재발송(retry)
export type ConfirmCodeFailure =
  | { kind: 'duplicateEmail'; message: string }
  | { kind: 'retry'; message: string }

function confirmCodeRetryMessage(error: unknown) {
  switch (apiErrorCode(error)) {
    case 'EMAIL_VERIFICATION_CODE_MISMATCH':
      return '인증 코드가 일치하지 않아요.'
    case 'EMAIL_VERIFICATION_CODE_EXPIRED':
      return '인증 코드가 만료됐어요. 다시 발송해주세요.'
    case 'EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED':
      return '확인 횟수를 초과했어요. 새 코드를 요청해주세요.'
    case 'VALIDATION_FAILED':
      return '인증 코드 6자리를 입력해주세요.'
    case 'INVALID_EMAIL':
      return EMAIL_FORMAT_MESSAGE
    default:
      return fallbackMessage(error)
  }
}

// 2단계(1.2.2) 코드 확인
export function confirmCodeFailure(error: unknown): ConfirmCodeFailure {
  if (apiErrorCode(error) === 'DUPLICATE_EMAIL') {
    return { kind: 'duplicateEmail', message: DUPLICATE_EMAIL_MESSAGE }
  }
  return { kind: 'retry', message: confirmCodeRetryMessage(error) }
}

function isSignupField(field: unknown): field is SignupField {
  return field === 'nickname' || field === 'password'
}

// 응답의 fieldErrors에서 화면 필드별 문구를 만든다. 필드마다 사유는 하나다(1.2.3).
export function signupFieldErrors(error: unknown): SignupFieldErrors {
  if (!axios.isAxiosError(error)) return {}
  const body = error.response?.data as FieldErrorBody | undefined
  const result: SignupFieldErrors = {}
  for (const item of body?.error?.fieldErrors ?? []) {
    if (isSignupField(item.field)) {
      result[item.field] = FIELD_RULE_MESSAGES[item.field]
    }
  }
  return result
}

// 3단계(1.2.3) 가입 완료
export function signupFailure(error: unknown): SignupFailure {
  const code = apiErrorCode(error)
  if (code === 'EMAIL_SIGNUP_CONTEXT_INVALID') {
    return { kind: 'context', message: SIGNUP_CONTEXT_MESSAGE }
  }
  if (code === 'DUPLICATE_EMAIL') {
    return { kind: 'duplicateEmail', message: DUPLICATE_EMAIL_MESSAGE }
  }
  if (code === 'DUPLICATE_NICKNAME') {
    return {
      kind: 'fields',
      fieldErrors: { nickname: '이미 사용 중인 닉네임이에요.' },
    }
  }
  if (
    code === 'VALIDATION_FAILED' ||
    code === 'INVALID_PASSWORD' ||
    code === 'INVALID_NICKNAME'
  ) {
    const fieldErrors = signupFieldErrors(error)
    // fieldErrors가 비어 있으면 코드로 필드를 정한다
    if (code === 'INVALID_PASSWORD') {
      fieldErrors.password ??= PASSWORD_RULE_MESSAGE
    }
    if (code === 'INVALID_NICKNAME') {
      fieldErrors.nickname ??= NICKNAME_RULE_MESSAGE
    }
    if (Object.keys(fieldErrors).length > 0) {
      return { kind: 'fields', fieldErrors }
    }
  }
  return { kind: 'general', message: fallbackMessage(error) }
}
