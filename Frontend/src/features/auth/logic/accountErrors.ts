import axios from 'axios'
import { StorageUploadError } from '../../../api/storage/upload'
import { AUTH_TEMPORARILY_UNAVAILABLE_MESSAGE, apiErrorCode } from './authUser'
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
    // 비밀번호는 바뀌지 않았고 세션도 유지되므로 입력을 그대로 두고 다시 보낼 수 있다(MEMBER_AUTH 1.11)
    case 'AUTH_TEMPORARILY_UNAVAILABLE':
      return { kind: 'general', message: AUTH_TEMPORARILY_UNAVAILABLE_MESSAGE }
  }
  return { kind: 'general', message: fallbackMessage(error) }
}

// 프로필 이미지 등록·삭제(IMAGE_UPLOAD 1.2, MEMBER_AUTH 1.12·1.13, GR-132 M00-05).
// 입력이 파일 선택 하나라 필드별로 나누지 않고 모달 안 한 곳에 문구 하나를 표시한다.

export const PROFILE_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
export const PROFILE_IMAGE_MAX_SIZE = 5 * 1024 * 1024
const PROFILE_IMAGE_NAME_MAX_LENGTH = 255

export const PROFILE_IMAGE_TYPE_MESSAGE =
  'JPG, PNG, WebP 이미지만 올릴 수 있어요.'
export const PROFILE_IMAGE_SIZE_MESSAGE = '5MB 이하의 이미지를 올려주세요.'
export const PROFILE_IMAGE_NAME_MESSAGE = '파일명은 255자 이하로 지정해주세요.'
export const PROFILE_IMAGE_UPLOAD_FAILED_MESSAGE =
  '이미지를 올리지 못했어요. 잠시 뒤 다시 시도해주세요.'
export const PROFILE_IMAGE_REGISTER_FAILED_MESSAGE =
  '이미지를 등록하지 못했어요. 다시 시도해주세요.'

// 발급 요청의 검증 필드 → 사전 검증과 같은 문구. 화면 사전 검증 순서(형식 → 크기 → 파일명)를 따른다.
const UPLOAD_FIELD_MESSAGES: [string, string][] = [
  ['contentType', PROFILE_IMAGE_TYPE_MESSAGE],
  ['fileSize', PROFILE_IMAGE_SIZE_MESSAGE],
  ['fileName', PROFILE_IMAGE_NAME_MESSAGE],
]

// 파일을 고를 때 검사한다. 위반이면 문구를 돌려주고 미리보기·요청을 하지 않는다. 통과하면 빈 문자열.
export function profileImageError(file: File): string {
  if (!PROFILE_IMAGE_TYPES.includes(file.type))
    return PROFILE_IMAGE_TYPE_MESSAGE
  if (file.size === 0 || file.size > PROFILE_IMAGE_MAX_SIZE)
    return PROFILE_IMAGE_SIZE_MESSAGE
  if (file.name.length > PROFILE_IMAGE_NAME_MAX_LENGTH)
    return PROFILE_IMAGE_NAME_MESSAGE
  return ''
}

// 발급·Storage 업로드·등록·삭제 실패 → 문구. 같은 VALIDATION_FAILED라도 field로 단계를 가른다.
export function profileImageFailure(error: unknown): string {
  if (error instanceof StorageUploadError)
    return PROFILE_IMAGE_UPLOAD_FAILED_MESSAGE
  switch (apiErrorCode(error)) {
    case 'EXTERNAL_SERVICE_ERROR':
      return PROFILE_IMAGE_UPLOAD_FAILED_MESSAGE
    case 'VALIDATION_FAILED': {
      const fields = responseFields(error)
      if (fields.includes('imageUrl'))
        return PROFILE_IMAGE_REGISTER_FAILED_MESSAGE
      const matched = UPLOAD_FIELD_MESSAGES.find(([field]) =>
        fields.includes(field),
      )
      if (matched) return matched[1]
      break
    }
  }
  return fallbackMessage(error)
}
