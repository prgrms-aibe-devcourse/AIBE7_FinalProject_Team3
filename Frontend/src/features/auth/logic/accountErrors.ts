import axios from 'axios'
import { apiErrorCode } from './authUser'
import { DUPLICATE_NICKNAME_MESSAGE, fallbackMessage } from './signupErrors'
import {
  NICKNAME_RULE_MESSAGE,
  PASSWORD_RULE_MESSAGE,
} from './signupValidation'

// 마이페이지 닉네임 수정(MEMBER_AUTH 1.10)·비밀번호 변경(1.11) 오류 → 화면 처리(GR-131 M00-02).
// 코드와 field로 분기하고 서버의 reason 문구는 쓰지 않는다. 401은 공통 클라이언트가 처리하므로 분기하지 않는다.

export const NICKNAME_UNCHANGED_MESSAGE = '현재 닉네임과 같아요.'
export const PASSWORD_MISMATCH_MESSAGE = '현재 비밀번호가 일치하지 않아요.'
export const PASSWORD_UNCHANGED_MESSAGE =
  '현재 비밀번호와 다른 비밀번호를 입력해주세요.'
export const CURRENT_PASSWORD_REQUIRED_MESSAGE = '현재 비밀번호를 입력해주세요.'
export const NEW_PASSWORD_REQUIRED_MESSAGE = '새 비밀번호를 입력해주세요.'
// 새 비밀번호 확인은 화면에서만 비교하고 서버로 보내지 않는다(GR-160)
export const NEW_PASSWORD_CONFIRM_REQUIRED_MESSAGE =
  '새 비밀번호를 한 번 더 입력해주세요.'
export const NEW_PASSWORD_CONFIRM_MISMATCH_MESSAGE =
  '새 비밀번호가 일치하지 않아요.'
const PASSWORD_NOT_AVAILABLE_MESSAGE = '이 계정은 비밀번호를 변경할 수 없어요.'

export type PasswordField = 'currentPassword' | 'newPassword'
export type PasswordFieldErrors = Partial<Record<PasswordField, string>>
// 화면 폼의 입력. 확인 입력은 화면 사전 검증에만 쓴다.
export type PasswordFormField = PasswordField | 'newPasswordConfirm'
export type PasswordFormErrors = Partial<Record<PasswordFormField, string>>

// 입력 아래에 표시(fields), 폼 하단 표시(general)
export type NicknameUpdateFailure =
  | { kind: 'fields'; fieldErrors: { nickname: string } }
  | { kind: 'general'; message: string }

// resetCurrent: 현재 비밀번호 불일치면 그 입력만 비우고 포커스한다(M00-04)
export type PasswordChangeFailure =
  | { kind: 'fields'; fieldErrors: PasswordFieldErrors; resetCurrent: boolean }
  | { kind: 'general'; message: string }

type FieldErrorBody = { error?: { fieldErrors?: { field?: string }[] } }

function responseFields(error: unknown) {
  if (!axios.isAxiosError(error)) return []
  const body = error.response?.data as FieldErrorBody | undefined
  return (body?.error?.fieldErrors ?? []).map((item) => item.field)
}

export function nicknameUpdateFailure(error: unknown): NicknameUpdateFailure {
  switch (apiErrorCode(error)) {
    // 요청 본문은 닉네임 하나뿐이라 검증 오류는 닉네임 규칙으로 안내한다
    case 'INVALID_NICKNAME':
    case 'VALIDATION_FAILED':
      return {
        kind: 'fields',
        fieldErrors: { nickname: NICKNAME_RULE_MESSAGE },
      }
    case 'NICKNAME_UNCHANGED':
      return {
        kind: 'fields',
        fieldErrors: { nickname: NICKNAME_UNCHANGED_MESSAGE },
      }
    case 'DUPLICATE_NICKNAME':
      return {
        kind: 'fields',
        fieldErrors: { nickname: DUPLICATE_NICKNAME_MESSAGE },
      }
    default:
      return { kind: 'general', message: fallbackMessage(error) }
  }
}

// 필수값 누락(VALIDATION_FAILED)은 fieldErrors의 field로 입력을 정한다
function requiredFieldErrors(error: unknown): PasswordFieldErrors {
  const fieldErrors: PasswordFieldErrors = {}
  for (const field of responseFields(error)) {
    if (field === 'currentPassword') {
      fieldErrors.currentPassword = CURRENT_PASSWORD_REQUIRED_MESSAGE
    }
    if (field === 'newPassword') {
      fieldErrors.newPassword = NEW_PASSWORD_REQUIRED_MESSAGE
    }
  }
  return fieldErrors
}

export function passwordChangeFailure(error: unknown): PasswordChangeFailure {
  const fields = (fieldErrors: PasswordFieldErrors, resetCurrent = false) => ({
    kind: 'fields' as const,
    fieldErrors,
    resetCurrent,
  })
  switch (apiErrorCode(error)) {
    case 'PASSWORD_MISMATCH':
      return fields({ currentPassword: PASSWORD_MISMATCH_MESSAGE }, true)
    case 'INVALID_PASSWORD':
      return fields({ newPassword: PASSWORD_RULE_MESSAGE })
    case 'PASSWORD_UNCHANGED':
      return fields({ newPassword: PASSWORD_UNCHANGED_MESSAGE })
    case 'VALIDATION_FAILED': {
      const fieldErrors = requiredFieldErrors(error)
      if (Object.keys(fieldErrors).length > 0) return fields(fieldErrors)
      break
    }
    case 'PASSWORD_NOT_AVAILABLE':
      return { kind: 'general', message: PASSWORD_NOT_AVAILABLE_MESSAGE }
  }
  return { kind: 'general', message: fallbackMessage(error) }
}
