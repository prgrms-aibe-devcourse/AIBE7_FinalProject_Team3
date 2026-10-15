import { AxiosError, type AxiosResponse } from 'axios'
import { describe, expect, it } from 'vitest'
import {
  hasRole,
  isAdmin,
  isSeller,
  loginErrorMessage,
  toAuthUser,
  type AuthUser,
} from '../logic/authUser'

const raw = {
  userId: 'id-1',
  email: 'grab@example.com',
  nickname: '드롭헌터',
  provider: 'LOCAL' as const,
  roles: ['USER', 'SELLER'],
  profileImageUrl: null,
  createdAt: '2026-10-15T00:21:34.835588Z',
}

const axiosError = (status: number, data?: unknown) => {
  const response = { status, data } as AxiosResponse
  return new AxiosError('failed', String(status), undefined, null, response)
}

describe('toAuthUser', () => {
  it('응답을 화면용 모델로 변환한다', () => {
    expect(toAuthUser(raw)).toEqual(raw)
  })

  it('빠진 필드에 기본값을 채운다', () => {
    const user = toAuthUser({ email: 'a@b.com', nickname: 'a' })
    expect(user.userId).toBe('')
    expect(user.provider).toBe('LOCAL')
    expect(user.roles).toEqual(['USER'])
    expect(user.profileImageUrl).toBeNull()
  })
})

describe('역할 판단', () => {
  const user: AuthUser = toAuthUser(raw)
  it('roles 배열로 판단한다', () => {
    expect(hasRole(user, 'SELLER')).toBe(true)
    expect(isSeller(user)).toBe(true)
    expect(isAdmin(user)).toBe(false)
    expect(hasRole(null, 'USER')).toBe(false)
  })
})

describe('loginErrorMessage', () => {
  it('오류 코드로 문구를 고른다', () => {
    expect(
      loginErrorMessage(
        axiosError(401, { error: { code: 'INVALID_CREDENTIALS' } }),
      ),
    ).toBe('이메일 또는 비밀번호를 확인해주세요.')
    expect(
      loginErrorMessage(
        axiosError(403, { error: { code: 'ACCOUNT_DISABLED' } }),
      ),
    ).toBe('사용할 수 없는 계정이에요.')
    expect(
      loginErrorMessage(
        axiosError(400, { error: { code: 'VALIDATION_FAILED' } }),
      ),
    ).toBe('이메일과 비밀번호 형식을 확인해주세요.')
  })

  it('네트워크·5xx는 연결 실패로 안내한다', () => {
    expect(loginErrorMessage(axiosError(500))).toBe(
      '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
    )
    expect(loginErrorMessage(new Error('network'))).toBe(
      '연결에 실패했어요. 잠시 뒤 다시 시도해주세요.',
    )
  })
})
