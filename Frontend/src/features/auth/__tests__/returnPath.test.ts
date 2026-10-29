import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_RETURN_PATH,
  safeReturnPath,
  saveSocialReturnPath,
  takeSocialReturnPath,
} from '../logic/returnPath'

// Node에는 sessionStorage가 없어 Map으로 대신한다
function stubSessionStorage() {
  const store = new Map<string, string>()
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
  })
  return store
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('safeReturnPath', () => {
  it('앱 내부 경로는 그대로 쓴다', () => {
    expect(safeReturnPath('/my')).toBe('/my')
    expect(safeReturnPath('/seller/drops?status=DRAFT')).toBe(
      '/seller/drops?status=DRAFT',
    )
  })

  it('외부 주소·내부 경로가 아닌 값은 기본 경로로 바꾼다', () => {
    for (const value of [
      '//evil.example',
      'https://evil.example',
      'javascript:alert(1)',
      'my',
      '',
      null,
      undefined,
      1,
    ]) {
      expect(safeReturnPath(value)).toBe(DEFAULT_RETURN_PATH)
    }
  })

  // 로그아웃·세션 만료로 관리자 페이지의 !user 가드가 자기 경로를 from으로 남긴 뒤
  // 다른 계정이 로그인하면, 그 계정의 권한을 확인하지 않고 관리자 경로로 보내던 버그(GR-132)
  it('관리자 전용 경로는 기본 경로로 바꾼다', () => {
    expect(safeReturnPath('/admin/seller-applications')).toBe(
      DEFAULT_RETURN_PATH,
    )
    expect(safeReturnPath('/admin')).toBe(DEFAULT_RETURN_PATH)
  })
})

describe('소셜 복귀 경로', () => {
  it('저장한 경로를 한 번만 읽고 지운다', () => {
    const store = stubSessionStorage()
    saveSocialReturnPath('/my/orders/1')
    expect([...store.values()]).toEqual(['/my/orders/1'])

    expect(takeSocialReturnPath()).toBe('/my/orders/1')
    expect(store.size).toBe(0)
    expect(takeSocialReturnPath()).toBe(DEFAULT_RETURN_PATH)
  })

  it('저장된 값도 다시 검사하고 외부 주소면 지운 뒤 기본 경로로 보낸다', () => {
    const store = stubSessionStorage()
    store.set('grab.socialReturnTo', '//evil.example')

    expect(takeSocialReturnPath()).toBe(DEFAULT_RETURN_PATH)
    expect(store.size).toBe(0)
  })

  it('저장소를 쓸 수 없으면 오류 없이 기본 경로로 보낸다', () => {
    const blocked = () => {
      throw new Error('blocked')
    }
    vi.stubGlobal('sessionStorage', {
      getItem: blocked,
      setItem: blocked,
      removeItem: blocked,
    })

    expect(() => saveSocialReturnPath('/my')).not.toThrow()
    expect(takeSocialReturnPath()).toBe(DEFAULT_RETURN_PATH)
  })
})
