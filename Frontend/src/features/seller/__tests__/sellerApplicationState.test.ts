import { expect, test } from 'vitest'
import {
  adminApplicationErrorMessage,
  applicationErrorMessage,
  isNoApplication,
} from '../logic/sellerApplicationState'

function axiosError(status: number, code?: string) {
  return {
    isAxiosError: true,
    response: { status, data: code ? { error: { code } } : undefined },
  }
}

test('404는 신청 없음으로 판정한다', () => {
  expect(isNoApplication(axiosError(404))).toBe(true)
  expect(isNoApplication(axiosError(400))).toBe(false)
  expect(isNoApplication(new Error('network'))).toBe(false)
})

test('에러 코드별로 안내 문구를 맞춘다', () => {
  expect(applicationErrorMessage(axiosError(401))).toContain('로그인')
  expect(applicationErrorMessage(axiosError(403))).toContain('로그인')
  expect(
    applicationErrorMessage(axiosError(422, 'BUSINESS_NOT_ACTIVE')),
  ).toContain('휴업')
  expect(
    applicationErrorMessage(
      axiosError(409, 'SELLER_APPLICATION_ALREADY_EXISTS'),
    ),
  ).toContain('이미 접수')
  expect(
    applicationErrorMessage(
      axiosError(409, 'DUPLICATE_BUSINESS_REGISTRATION_NUMBER'),
    ),
  ).toContain('다른 계정')
  expect(
    applicationErrorMessage(axiosError(400, 'VALIDATION_FAILED')),
  ).toContain('서류')
  expect(applicationErrorMessage(axiosError(500))).toContain('처리하지')
  expect(applicationErrorMessage(new Error('network'))).toContain('처리하지')
})

test('관리자 심사 에러 코드별로 안내 문구를 맞춘다', () => {
  expect(adminApplicationErrorMessage(axiosError(401))).toContain('관리자 권한')
  expect(adminApplicationErrorMessage(axiosError(403))).toContain('관리자 권한')
  expect(
    adminApplicationErrorMessage(axiosError(409, 'INVALID_STATE_TRANSITION')),
  ).toContain('이미 처리된')
  expect(
    adminApplicationErrorMessage(axiosError(400, 'VALIDATION_FAILED')),
  ).toContain('반려 사유')
  expect(adminApplicationErrorMessage(axiosError(404))).toContain(
    '찾을 수 없어요',
  )
  expect(adminApplicationErrorMessage(axiosError(500))).toContain('처리하지')
  expect(adminApplicationErrorMessage(new Error('network'))).toContain(
    '처리하지',
  )
})
