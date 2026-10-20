import { describe, expect, it } from 'vitest'
import { nicknameError, passwordError } from '../logic/signupValidation'

describe('가입 입력 사전 검증', () => {
  it('닉네임 길이와 허용 문자를 확인한다', () => {
    expect(nicknameError(' 드롭헌터_1 ')).toBe('')
    expect(nicknameError('a')).toBeTruthy()
    expect(nicknameError('홍 길동')).toBeTruthy()
    expect(nicknameError('가나다라마바사아자차카')).toBeTruthy()
  })

  it('비밀번호 길이·문자 조합·공백을 확인한다', () => {
    expect(passwordError('Password123!')).toBe('')
    expect(passwordError('password1234')).toBeTruthy()
    expect(passwordError('Password 123!')).toBeTruthy()
    expect(passwordError('비밀번호123!')).toBeTruthy()
  })
})
