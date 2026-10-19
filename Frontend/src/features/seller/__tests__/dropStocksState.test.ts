import { expect, test } from 'vitest'
import { isDropNotAccessible } from '../logic/dropStocksState'

function axiosError(status: number) {
  return { isAxiosError: true, response: { status } }
}

test('404·403은 접근 불가로 판정하고 그 외는 아니다', () => {
  expect(isDropNotAccessible(axiosError(404))).toBe(true)
  expect(isDropNotAccessible(axiosError(403))).toBe(true)
  expect(isDropNotAccessible(axiosError(400))).toBe(false)
  expect(isDropNotAccessible(new Error('network'))).toBe(false)
})
