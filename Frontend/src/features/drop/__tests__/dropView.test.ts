import { describe, expect, it } from 'vitest'
import { toCardDrops } from '../logic/dropView'

describe('공개 목록 응답 → 카드 모델', () => {
  it('목록 응답의 필드를 카드가 쓰는 형태로 옮긴다', () => {
    expect(
      toCardDrops([
        {
          dropId: 100,
          name: '한정판 스니커즈',
          brandName: 'SLOW OBJECT',
          thumbnailUrl: 'https://example.com/a.jpg',
          minPrice: 129000,
          status: 'WISH',
          soldOut: false,
          availableStock: 24,
          wishCount: 152,
          saleStartsAt: '2026-09-20T10:00:00+09:00',
          saleEndsAt: '2026-09-20T12:00:00+09:00',
        },
      ]),
    ).toEqual([
      {
        id: 100,
        name: '한정판 스니커즈',
        image: 'https://example.com/a.jpg',
        status: 'WISH',
        wishCount: 152,
        saleStartsAt: '2026-09-20T10:00:00+09:00',
        saleEndsAt: '2026-09-20T12:00:00+09:00',
        brand: 'SLOW OBJECT',
        minPrice: 129000,
        availableStock: 24,
        soldOut: false,
      },
    ])
  })

  it('썸네일이 없으면 null, 값이 있으면 가격을 그대로 옮긴다', () => {
    const [card] = toCardDrops([
      { dropId: 1, name: '옵션 없는 DROP', minPrice: 9900 },
    ])
    expect(card.image).toBeNull()
    expect(card.minPrice).toBe(9900)
    expect(card.soldOut).toBeUndefined()
  })

  it('식별자가 없는 항목은 카드로 만들지 않는다', () => {
    expect(
      toCardDrops([{ name: '식별자 없음' }, { dropId: 2, name: '정상' }]),
    ).toHaveLength(1)
  })
})
