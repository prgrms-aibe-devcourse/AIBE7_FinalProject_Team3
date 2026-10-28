import { expect, test } from 'vitest'
import type { SimilarSales } from '../../../api/demand/similarSales'
import {
  basisLine,
  minutesLabel,
  othersSentence,
  ownSentence,
  salesFacts,
  toSimilarSalesRequest,
} from '../logic/similarSales'

const BLACK = 'sku:{"g1":"black"}'
const WHITE = 'sku:{"g1":"white"}'

const others = {
  selection: 'SIMILAR',
  scope: 'ALL_SELLERS',
  dropCountBand: '10_TO_19',
  soldOutRate: 0.4,
  medianSellOutMinutes: 15,
  sellThroughRate: 0.81,
  soldPerWish: 0.35,
} as const

test('상품명·설명·옵션과 활성 SKU 가격으로 요청을 만들고, 빈 값은 보내지 않는다', () => {
  const groups = [
    {
      id: 'g1',
      name: ' 색상 ',
      rawValues: '',
      values: [
        { id: 'black', label: '블랙' },
        { id: 'blank', label: ' ' },
      ],
    },
    { id: 'g2', name: '', rawValues: '', values: [{ id: 'x', label: 'X' }] },
  ]

  expect(
    toSimilarSalesRequest(
      {
        categoryId: '8',
        name: ' 고양이 키링 ',
        description: '',
        [`${BLACK}:price`]: '12000',
        [`${WHITE}:price`]: '14000',
      },
      groups,
    ),
  ).toEqual({
    categoryId: 8,
    name: '고양이 키링',
    description: undefined,
    optionGroups: [{ name: '색상', values: ['블랙'] }],
    unitPrices: [12000, 14000],
  })
  expect(toSimilarSalesRequest({ categoryId: '8' }, groups)).toBeNull()
})

test('기준과 이유를 한 줄로 쓴다', () => {
  const own = { selection: 'SIMILAR', dropCount: 2 } as const
  const line = (data: Partial<SimilarSales>) =>
    basisLine({ others, ...data } as SimilarSales)

  expect(
    line({ basis: 'OWN', reason: 'OWN_ENOUGH', own: { dropCount: 4 } }),
  ).toBe('내 비슷한 DROP 4개 기준')
  expect(line({ basis: 'OTHERS', reason: 'OWN_FEW', own })).toBe(
    '내 비슷한 DROP이 2개라 다른 판매자의 비슷한 굿즈를 기준으로 했어요.',
  )
  expect(
    line({
      basis: 'OTHERS',
      reason: 'FIRST_SALE',
      others: { ...others, scope: 'NEW_SELLERS', selection: 'CATEGORY' },
    }),
  ).toBe(
    '첫 판매라 처음 판매한 다른 판매자들의 같은 카테고리 굿즈를 기준으로 했어요.',
  )
  expect(line({ basis: null, reason: 'FIRST_SALE' })).toContain('아직 부족해요')
})

test('매진 결과를 문장으로 쓰고 다른 판매자 표본 수는 구간으로만 쓴다', () => {
  expect(
    ownSentence({
      selection: 'SIMILAR',
      dropCount: 4,
      soldOutRate: 0.75,
      medianSellOutMinutes: 6,
    }),
  ).toBe('비슷한 DROP 4개 중 3개가 매진됐고, 매진까지 보통 6분 걸렸어요.')
  expect(ownSentence({ selection: 'ALL', dropCount: 2, soldOutRate: 0 })).toBe(
    '같은 카테고리 DROP 2개 중 매진된 DROP은 없었어요.',
  )
  expect(othersSentence(others)).toBe(
    '다른 판매자의 비슷한 굿즈 10~19개 중 40%가 매진됐고, 매진까지 보통 15분 걸렸어요.',
  )
  expect(salesFacts(others)).toBe(
    '판매율 81% · 판매 시작 WISH 1명당 0.35개 판매',
  )
  expect(minutesLabel(150)).toBe('2시간 30분')
  expect(minutesLabel(120)).toBe('2시간')
})
