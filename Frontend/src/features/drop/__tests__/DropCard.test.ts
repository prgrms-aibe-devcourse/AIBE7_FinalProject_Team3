import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import DropCard from '../DropCard'
import { catalogDrops } from '../mock/mockCatalog'
import type { Drop } from '../../../types/drop'

const render = (drop: Drop, mode: 'WISH' | 'GRAB') =>
  renderToStaticMarkup(
    createElement(
      MemoryRouter,
      {},
      createElement(DropCard, { drop, mode, wished: false, onWish: () => {} }),
    ),
  )

describe('상품 카드 상태', () => {
  it('비활성 옵션은 최저 가격과 가용 재고에 포함하지 않는다', () => {
    const html = render(
      {
        ...catalogDrops[0],
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
    const html = render({ ...catalogDrops[0], skus: [] }, 'GRAB')
    expect(html).toContain('판매 옵션 없음')
    expect(html).toContain('SOLD OUT')
    expect(html).toContain('품절 · 상세 보기 ↗')
    expect(html).toContain('/drops/101')
  })

  it('이미지가 없으면 대체 설명을 제공하며 긴 상품명은 링크에 보존한다', () => {
    const name =
      '한정판 캔버스 토트와 데일리 스트랩을 함께 구성한 아주 긴 상품 이름'
    const html = render({ ...catalogDrops[0], image: '', name }, 'WISH')
    expect(html).toContain(`${name} 이미지 없음`)
    expect(html).toContain(`>${name}</a>`)
    expect(html).not.toContain('src=""')
  })

  it('판매가 시작된 WISH는 등록을 막고 인기 GRAB 영역에서는 상세 링크를 표시한다', () => {
    const delayed = { ...catalogDrops[0], saleStartsAt: '2020-01-01T00:00:00Z' }
    const wish = render(delayed, 'WISH')
    expect(wish).toContain('disabled=""')
    expect(wish).toContain('WISH 마감')
    const grab = render(delayed, 'GRAB')
    expect(grab).toContain('GRAB ↗')
    expect(grab).not.toContain('♡ WISH')
  })
})
