import { AxiosError, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  CURRENT_PASSWORD_REQUIRED_MESSAGE,
  NEW_PASSWORD_REQUIRED_MESSAGE,
  NICKNAME_UNCHANGED_MESSAGE,
  nicknameUpdateFailure,
  PASSWORD_MISMATCH_MESSAGE,
  PASSWORD_UNCHANGED_MESSAGE,
  passwordChangeFailure,
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
