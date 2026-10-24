import { AxiosError, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import { StorageUploadError } from '../../../api/storage/upload'
import {
  CURRENT_PASSWORD_REQUIRED_MESSAGE,
  NEW_PASSWORD_REQUIRED_MESSAGE,
  NICKNAME_UNCHANGED_MESSAGE,
  nicknameUpdateFailure,
  PASSWORD_MISMATCH_MESSAGE,
  PASSWORD_UNCHANGED_MESSAGE,
  passwordChangeFailure,
  PROFILE_IMAGE_MAX_SIZE,
  PROFILE_IMAGE_NAME_MESSAGE,
  PROFILE_IMAGE_REGISTER_FAILED_MESSAGE,
  PROFILE_IMAGE_SIZE_MESSAGE,
  PROFILE_IMAGE_TYPE_MESSAGE,
  PROFILE_IMAGE_UPLOAD_FAILED_MESSAGE,
  profileImageError,
  profileImageFailure,
} from '../logic/accountErrors'
import { DUPLICATE_NICKNAME_MESSAGE } from '../logic/signupErrors'
import {
  NICKNAME_RULE_MESSAGE,
  PASSWORD_RULE_MESSAGE,
} from '../logic/signupValidation'

const NETWORK = '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.'
const GENERAL = '요청을 처리하지 못했어요. 다시 시도해주세요.'

const apiError = (
  status: number,
  code: string,
  fieldErrors: { field: string; reason: string }[] = [],
) => {
  const response = {
    status,
    data: { success: false, error: { code, fieldErrors } },
  } as AxiosResponse
  return new AxiosError('failed', String(status), undefined, null, response)
}

const networkError = () => new AxiosError('Network Error', 'ERR_NETWORK')

describe('nicknameUpdateFailure', () => {
  it.each([
    ['INVALID_NICKNAME', 400, NICKNAME_RULE_MESSAGE],
    ['VALIDATION_FAILED', 400, NICKNAME_RULE_MESSAGE],
    ['NICKNAME_UNCHANGED', 400, NICKNAME_UNCHANGED_MESSAGE],
    ['DUPLICATE_NICKNAME', 409, DUPLICATE_NICKNAME_MESSAGE],
  ])('%s는 닉네임 입력 아래에 표시한다', (code, status, message) => {
    expect(nicknameUpdateFailure(apiError(status, code))).toEqual({
      kind: 'fields',
      fieldErrors: { nickname: message },
    })
  })

  it('네트워크·5xx는 연결 실패, 그 밖은 일반 실패로 폼 하단에 표시한다', () => {
    expect(nicknameUpdateFailure(networkError())).toEqual({
      kind: 'general',
      message: NETWORK,
    })
    expect(
      nicknameUpdateFailure(apiError(500, 'INTERNAL_SERVER_ERROR')),
    ).toEqual({ kind: 'general', message: NETWORK })
    expect(nicknameUpdateFailure(apiError(403, 'ACCESS_DENIED'))).toEqual({
      kind: 'general',
      message: GENERAL,
    })
    expect(nicknameUpdateFailure(new Error('형식 오류'))).toEqual({
      kind: 'general',
      message: GENERAL,
    })
  })
})

describe('passwordChangeFailure', () => {
  it('현재 비밀번호 불일치는 현재 비밀번호 아래에 표시하고 그 입력을 비우게 한다', () => {
    expect(passwordChangeFailure(apiError(400, 'PASSWORD_MISMATCH'))).toEqual({
      kind: 'fields',
      fieldErrors: { currentPassword: PASSWORD_MISMATCH_MESSAGE },
      resetCurrent: true,
    })
  })

  it.each([
    ['INVALID_PASSWORD', PASSWORD_RULE_MESSAGE],
    ['PASSWORD_UNCHANGED', PASSWORD_UNCHANGED_MESSAGE],
  ])('%s는 새 비밀번호 아래에 표시한다', (code, message) => {
    expect(passwordChangeFailure(apiError(400, code))).toEqual({
      kind: 'fields',
      fieldErrors: { newPassword: message },
      resetCurrent: false,
    })
  })

  it('필수값 누락은 fieldErrors의 field로 입력을 정한다', () => {
    expect(
      passwordChangeFailure(
        apiError(400, 'VALIDATION_FAILED', [
          { field: 'currentPassword', reason: '필수' },
          { field: 'newPassword', reason: '필수' },
        ]),
      ),
    ).toEqual({
      kind: 'fields',
      fieldErrors: {
        currentPassword: CURRENT_PASSWORD_REQUIRED_MESSAGE,
        newPassword: NEW_PASSWORD_REQUIRED_MESSAGE,
      },
      resetCurrent: false,
    })
  })

  it('필드를 알 수 없는 필수값 오류는 폼 하단 일반 실패다', () => {
    expect(passwordChangeFailure(apiError(400, 'VALIDATION_FAILED'))).toEqual({
      kind: 'general',
      message: GENERAL,
    })
  })

  it('비밀번호가 없는 계정은 폼 하단에 표시한다', () => {
    expect(
      passwordChangeFailure(apiError(400, 'PASSWORD_NOT_AVAILABLE')),
    ).toEqual({
      kind: 'general',
      message: '이 계정은 비밀번호를 변경할 수 없어요.',
    })
  })

  it('네트워크·5xx는 연결 실패, 그 밖은 일반 실패다', () => {
    expect(passwordChangeFailure(networkError())).toEqual({
      kind: 'general',
      message: NETWORK,
    })
    expect(passwordChangeFailure(apiError(502, 'BAD_GATEWAY'))).toEqual({
      kind: 'general',
      message: NETWORK,
    })
    expect(passwordChangeFailure(apiError(403, 'ACCESS_DENIED'))).toEqual({
      kind: 'general',
      message: GENERAL,
    })
  })
})

describe('profileImageError', () => {
  const file = (size: number, type = 'image/png', name = 'profile.png') =>
    new File([new Uint8Array(size)], name, { type })

  it.each(['image/jpeg', 'image/png', 'image/webp'])(
    '%s는 5MB까지 통과한다',
    (type) => {
      expect(profileImageError(file(PROFILE_IMAGE_MAX_SIZE, type))).toBe('')
    },
  )

  it.each(['image/gif', 'image/svg+xml', ''])(
    '허용하지 않는 형식(%s)을 막는다',
    (type) => {
      expect(profileImageError(file(1, type))).toBe(PROFILE_IMAGE_TYPE_MESSAGE)
    },
  )

  it('0바이트와 5MB 초과를 막는다', () => {
    expect(profileImageError(file(0))).toBe(PROFILE_IMAGE_SIZE_MESSAGE)
    expect(profileImageError(file(PROFILE_IMAGE_MAX_SIZE + 1))).toBe(
      PROFILE_IMAGE_SIZE_MESSAGE,
    )
  })

  it('파일명은 255자까지 통과하고 256자부터 막는다', () => {
    const name = (length: number) => `${'a'.repeat(length - 4)}.png`
    expect(profileImageError(file(1, 'image/png', name(255)))).toBe('')
    expect(profileImageError(file(1, 'image/png', name(256)))).toBe(
      PROFILE_IMAGE_NAME_MESSAGE,
    )
  })

  it('여러 규칙을 어기면 형식 → 크기 → 파일명 순서로 하나만 안내한다', () => {
    expect(profileImageError(file(0, 'image/gif', 'a'.repeat(300)))).toBe(
      PROFILE_IMAGE_TYPE_MESSAGE,
    )
    expect(profileImageError(file(0, 'image/png', 'a'.repeat(300)))).toBe(
      PROFILE_IMAGE_SIZE_MESSAGE,
    )
  })
})

describe('profileImageFailure', () => {
  it('Storage 업로드 실패(연결 실패 포함)와 발급의 Storage 연동 실패는 업로드 실패 문구다', () => {
    expect(profileImageFailure(new StorageUploadError(403))).toBe(
      PROFILE_IMAGE_UPLOAD_FAILED_MESSAGE,
    )
    expect(profileImageFailure(new StorageUploadError(0))).toBe(
      PROFILE_IMAGE_UPLOAD_FAILED_MESSAGE,
    )
    expect(profileImageFailure(apiError(502, 'EXTERNAL_SERVICE_ERROR'))).toBe(
      PROFILE_IMAGE_UPLOAD_FAILED_MESSAGE,
    )
  })

  it.each([
    ['contentType', PROFILE_IMAGE_TYPE_MESSAGE],
    ['fileSize', PROFILE_IMAGE_SIZE_MESSAGE],
    ['fileName', PROFILE_IMAGE_NAME_MESSAGE],
  ])('발급 검증 실패(%s)는 사전 검증과 같은 문구다', (field, message) => {
    expect(
      profileImageFailure(
        apiError(400, 'VALIDATION_FAILED', [{ field, reason: '위반' }]),
      ),
    ).toBe(message)
  })

  it('발급 검증 필드가 여럿이면 형식 → 크기 → 파일명 순서로 고른다', () => {
    expect(
      profileImageFailure(
        apiError(400, 'VALIDATION_FAILED', [
          { field: 'fileName', reason: '위반' },
          { field: 'fileSize', reason: '위반' },
        ]),
      ),
    ).toBe(PROFILE_IMAGE_SIZE_MESSAGE)
  })

  it('등록 검증 실패(imageUrl)는 등록 실패 문구다', () => {
    expect(
      profileImageFailure(
        apiError(400, 'VALIDATION_FAILED', [
          { field: 'imageUrl', reason: '형식' },
        ]),
      ),
    ).toBe(PROFILE_IMAGE_REGISTER_FAILED_MESSAGE)
  })

  it('필드를 알 수 없는 검증 실패·네트워크·5xx·권한·형식 오류는 공통 문구다', () => {
    expect(profileImageFailure(apiError(400, 'VALIDATION_FAILED'))).toBe(
      GENERAL,
    )
    expect(profileImageFailure(apiError(400, 'INVALID_REQUEST'))).toBe(GENERAL)
    expect(profileImageFailure(networkError())).toBe(NETWORK)
    expect(profileImageFailure(apiError(500, 'INTERNAL_SERVER_ERROR'))).toBe(
      NETWORK,
    )
    expect(profileImageFailure(apiError(403, 'ACCESS_DENIED'))).toBe(GENERAL)
    expect(profileImageFailure(new Error('응답 형식'))).toBe(GENERAL)
  })
})
