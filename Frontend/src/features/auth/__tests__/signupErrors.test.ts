import { AxiosError, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  confirmCodeFailure,
  DUPLICATE_EMAIL_MESSAGE,
  requestCodeErrorMessage,
  SIGNUP_CONTEXT_MESSAGE,
  signupFailure,
  signupFieldErrors,
} from '../logic/signupErrors'
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

describe('requestCodeErrorMessage', () => {
  it('이메일 형식 오류를 안내한다', () => {
    const message = '이메일 주소를 확인해주세요.'
    expect(requestCodeErrorMessage(apiError(400, 'INVALID_EMAIL'))).toBe(
      message,
    )
    expect(requestCodeErrorMessage(apiError(400, 'VALIDATION_FAILED'))).toBe(
      message,
    )
  })

  it('재발송 제한은 한도 종류와 관계없이 같은 문구다', () => {
    expect(
      requestCodeErrorMessage(
        apiError(429, 'EMAIL_VERIFICATION_RESEND_TOO_SOON'),
      ),
    ).toBe('인증 코드를 너무 자주 요청했어요. 잠시 뒤 다시 요청해주세요.')
  })

  it('네트워크·5xx는 연결 실패, 그 밖은 일반 실패다', () => {
    expect(requestCodeErrorMessage(networkError())).toBe(NETWORK)
    expect(requestCodeErrorMessage(apiError(500, 'INTERNAL_ERROR'))).toBe(
      NETWORK,
    )
    expect(requestCodeErrorMessage(apiError(403, 'ACCESS_DENIED'))).toBe(
      GENERAL,
    )
    expect(requestCodeErrorMessage(apiError(400, 'INVALID_REQUEST'))).toBe(
      GENERAL,
    )
    expect(requestCodeErrorMessage(new Error('x'))).toBe(GENERAL)
  })
})

describe('confirmCodeFailure', () => {
  it('코드 오류는 같은 단계에서 다시 시도한다', () => {
    expect(
      confirmCodeFailure(apiError(400, 'EMAIL_VERIFICATION_CODE_MISMATCH')),
    ).toEqual({ kind: 'retry', message: '인증 코드가 일치하지 않아요.' })
    expect(
      confirmCodeFailure(apiError(400, 'EMAIL_VERIFICATION_CODE_EXPIRED')),
    ).toEqual({
      kind: 'retry',
      message: '인증 코드가 만료됐어요. 다시 발송해주세요.',
    })
    expect(
      confirmCodeFailure(apiError(429, 'EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED')),
    ).toEqual({
      kind: 'retry',
      message: '확인 횟수를 초과했어요. 새 코드를 요청해주세요.',
    })
    expect(confirmCodeFailure(apiError(400, 'VALIDATION_FAILED'))).toEqual({
      kind: 'retry',
      message: '인증 코드 6자리를 입력해주세요.',
    })
  })

  it('이미 가입된 이메일은 로그인 안내로 분류한다', () => {
    expect(confirmCodeFailure(apiError(409, 'DUPLICATE_EMAIL'))).toEqual({
      kind: 'duplicateEmail',
      message: DUPLICATE_EMAIL_MESSAGE,
    })
  })

  it('처리하지 못한 오류는 연결·일반 실패 문구로 다시 시도한다', () => {
    expect(confirmCodeFailure(networkError())).toEqual({
      kind: 'retry',
      message: NETWORK,
    })
    expect(confirmCodeFailure(apiError(403, 'ACCESS_DENIED'))).toEqual({
      kind: 'retry',
      message: GENERAL,
    })
  })
})

describe('signupFieldErrors', () => {
  it('field로 규칙 문구를 정하고 reason은 쓰지 않는다', () => {
    expect(
      signupFieldErrors(
        apiError(400, 'INVALID_PASSWORD', [
          { field: 'password', reason: '서버 문구' },
        ]),
      ),
    ).toEqual({ password: PASSWORD_RULE_MESSAGE })
  })

  it('여러 필드를 함께 돌려준다', () => {
    expect(
      signupFieldErrors(
        apiError(400, 'VALIDATION_FAILED', [
          { field: 'password', reason: 'a' },
          { field: 'nickname', reason: 'b' },
        ]),
      ),
    ).toEqual({
      password: PASSWORD_RULE_MESSAGE,
      nickname: NICKNAME_RULE_MESSAGE,
    })
  })

  it('빈 배열·화면에 없는 필드·Axios 오류가 아닌 값은 빈 결과다', () => {
    expect(signupFieldErrors(apiError(400, 'VALIDATION_FAILED'))).toEqual({})
    expect(
      signupFieldErrors(
        apiError(400, 'VALIDATION_FAILED', [{ field: 'email', reason: 'a' }]),
      ),
    ).toEqual({})
    expect(signupFieldErrors(new Error('x'))).toEqual({})
  })
})

describe('signupFailure', () => {
  it('가입 컨텍스트 무효는 1단계로 돌아간다', () => {
    expect(
      signupFailure(apiError(401, 'EMAIL_SIGNUP_CONTEXT_INVALID')),
    ).toEqual({ kind: 'context', message: SIGNUP_CONTEXT_MESSAGE })
  })

  it('이미 가입된 이메일은 로그인 안내로 분류한다', () => {
    expect(signupFailure(apiError(409, 'DUPLICATE_EMAIL'))).toEqual({
      kind: 'duplicateEmail',
      message: DUPLICATE_EMAIL_MESSAGE,
    })
  })

  it('닉네임 중복은 닉네임 필드에 표시한다', () => {
    expect(signupFailure(apiError(409, 'DUPLICATE_NICKNAME'))).toEqual({
      kind: 'fields',
      fieldErrors: { nickname: '이미 사용 중인 닉네임이에요.' },
    })
  })

  it('검증 오류는 fieldErrors를 필드별로 표시한다', () => {
    expect(
      signupFailure(
        apiError(400, 'VALIDATION_FAILED', [
          { field: 'password', reason: 'a' },
          { field: 'nickname', reason: 'b' },
        ]),
      ),
    ).toEqual({
      kind: 'fields',
      fieldErrors: {
        password: PASSWORD_RULE_MESSAGE,
        nickname: NICKNAME_RULE_MESSAGE,
      },
    })
  })

  it('fieldErrors가 비어 있으면 코드로 필드를 정한다', () => {
    expect(signupFailure(apiError(400, 'INVALID_PASSWORD'))).toEqual({
      kind: 'fields',
      fieldErrors: { password: PASSWORD_RULE_MESSAGE },
    })
    expect(signupFailure(apiError(400, 'INVALID_NICKNAME'))).toEqual({
      kind: 'fields',
      fieldErrors: { nickname: NICKNAME_RULE_MESSAGE },
    })
  })

  it('필드를 정할 수 없는 오류는 폼 하단 문구다', () => {
    expect(signupFailure(apiError(400, 'VALIDATION_FAILED'))).toEqual({
      kind: 'general',
      message: GENERAL,
    })
    expect(signupFailure(apiError(400, 'INVALID_REQUEST'))).toEqual({
      kind: 'general',
      message: GENERAL,
    })
    expect(signupFailure(apiError(403, 'ACCESS_DENIED'))).toEqual({
      kind: 'general',
      message: GENERAL,
    })
    expect(signupFailure(apiError(503, 'SERVICE_UNAVAILABLE'))).toEqual({
      kind: 'general',
      message: NETWORK,
    })
    expect(signupFailure(networkError())).toEqual({
      kind: 'general',
      message: NETWORK,
    })
  })
})
