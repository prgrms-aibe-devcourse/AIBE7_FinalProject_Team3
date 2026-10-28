import { expect, test } from 'vitest'
import { relatedTitle } from '../logic/relatedDrops'

test('상세 상태와 추천 방식에 맞는 문구를 고르고 추천이 없으면 null이다', () => {
  expect(relatedTitle('wish', 'CO_WISH')).toBe(
    '이 DROP을 WISH한 사람들이 좋아할 만한 DROP',
  )
  expect(relatedTitle('wish', 'SIMILAR')).toBe('이런 DROP도 곧 열려요')
  expect(relatedTitle('closed', 'CO_WISH')).toBe('비슷한 굿즈가 곧 열려요')
  expect(relatedTitle('closed', 'SIMILAR')).toBe('비슷한 굿즈가 곧 열려요')
  expect(relatedTitle('wish', null)).toBeNull()
})
