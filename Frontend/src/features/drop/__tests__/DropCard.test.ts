import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DropCard from '../components/DropCard'
import type { CardDrop } from '../../../types/drop'

const base: CardDrop = {
  id: 101,
  name: '매일을 담는 캔버스 토트',
  image: 'https://example.com/image.jpg',
  status: 'WISH',
  wishCount: 10,
  saleStartsAt: '2026-10-04T10:00:00+09:00',
  saleEndsAt: '2026-10-11T10:00:00+09:00',
  skus: [{ id: 1, selections: {}, price: 1000, stock: 2 }],
}

const render = (drop: CardDrop, mode: 'WISH' | 'GRAB') =>
  renderToStaticMarkup(
    createElement(
      MemoryRouter,
      {},
      createElement(DropCard, { drop, mode, wished: false, onWish: () => {} }),
    ),
  )

afterEach(() => vi.restoreAllMocks())

describe('상품 카드 상태', () => {
  it.each(['WISH', 'GRAB'] as const)(
    '저장 상태가 %s여도 판매 중 GRAB 영역에는 배지를 표시하지 않는다',
    (status) => {
      const html = render({ ...base, status }, 'GRAB')
      expect(html).not.toContain('class="badge')
      expect(html).toContain('GRAB ↗')
    },
  )

  it('WISH 카드에는 오픈 D-day 배지를 유지한다', () => {
    vi.spyOn(Date, 'now').mockReturnValue(
      Date.parse('2026-10-01T10:00:00+09:00'),
    )
    const html = render(base, 'WISH')
    expect(html).toContain('OPEN D−3')
    expect(html).toContain('class="badge lime"')
  })
  it('비활성 옵션은 최저 가격과 가용 재고에 포함하지 않는다', () => {
    const html = render(
      {
        ...base,
        skus: [
          { id: 1, selections: {}, price: 100, stock: 99, active: false },
          { id: 2, selections: {}, price: 1000, stock: 2 },
        ],
      },
      'GRAB',
    )
    expect(html).toContain('1,000원')
    expect(html).toContain('남은 수량 <b>2개</b>')
  })

  it('활성 옵션이 없으면 판매 옵션 없음·품절을 표시하고 상세 탐색은 허용한다', () => {
    const html = render({ ...base, skus: [] }, 'GRAB')
    expect(html).toContain('판매 옵션 없음')
    expect(html).toContain('SOLD OUT')
    expect(html).toContain('품절 · 상세 보기 ↗')
    expect(html).toContain('/drops/101')
  })

  it('이미지가 없으면 대체 설명을 제공하며 긴 상품명은 링크에 보존한다', () => {
    const name =
      '한정판 캔버스 토트와 데일리 스트랩을 함께 구성한 아주 긴 상품 이름'
    const html = render({ ...base, image: '', name }, 'WISH')
    expect(html).toContain(`${name} 이미지 없음`)
    expect(html).toContain(`>${name}</a>`)
    expect(html).not.toContain('src=""')
  })

  it('판매가 시작된 WISH는 등록을 막고 인기 GRAB 영역에서는 상세 링크를 표시한다', () => {
    const delayed = { ...base, saleStartsAt: '2020-01-01T00:00:00Z' }
    const wish = render(delayed, 'WISH')
    expect(wish).toContain('disabled=""')
    expect(wish).toContain('WISH 마감')
    expect(wish).toContain('판매 시작')
    const grab = render(delayed, 'GRAB')
    expect(grab).toContain('GRAB ↗')
    expect(grab).not.toContain('♡ WISH')
  })
})
