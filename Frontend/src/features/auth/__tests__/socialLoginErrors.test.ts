import { expect, test } from 'vitest'
import {
  SOCIAL_LOGIN_INCOMPLETE_MESSAGE,
  socialCallbackErrorMessage,
} from '../logic/socialLoginErrors'

test('콜백 오류 코드를 로그인 화면 문구로 바꾼다', () => {
  expect(socialCallbackErrorMessage('OAUTH2_AUTHENTICATION_FAILED')).toBe(
    '소셜 로그인이 취소되었거나 실패했어요. 다시 시도해주세요.',
  )
  expect(socialCallbackErrorMessage('OAUTH2_EMAIL_REQUIRED')).toBe(
    '이메일 제공에 동의해야 가입할 수 있어요. 다시 시도해주세요.',
  )
  expect(socialCallbackErrorMessage('ACCOUNT_LINK_REQUIRED')).toBe(
    '이미 다른 방법으로 가입된 이메일이에요. 가입한 방법으로 로그인해주세요.',
  )
  expect(socialCallbackErrorMessage('ACCOUNT_DISABLED')).toBe(
    '사용할 수 없는 계정이에요.',
  )
  expect(socialCallbackErrorMessage('INTERNAL_SERVER_ERROR')).toBe(
    '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
  )
})

test('알 수 없는 값·객체 속성 이름·없음은 미완료 문구다', () => {
  for (const code of [
    'UNKNOWN',
    'toString',
    '__proto__',
    '',
    null,
    undefined,
  ]) {
    expect(socialCallbackErrorMessage(code)).toBe(
      SOCIAL_LOGIN_INCOMPLETE_MESSAGE,
    )
  }
})
