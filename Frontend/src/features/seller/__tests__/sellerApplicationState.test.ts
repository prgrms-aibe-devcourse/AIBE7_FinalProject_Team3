import { expect, test } from 'vitest'
import {
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
