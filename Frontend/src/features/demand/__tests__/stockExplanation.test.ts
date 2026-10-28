import { expect, test } from 'vitest'
import type { StockRecommendation } from '../../../api/demand/stockRecommendation'
import {
  explanationButtonLabel,
  toStockExplanationRequest,
} from '../logic/stockExplanation'

const BLACK = 'sku:{"g1":"black"}'
const groups = [
  {
    id: 'g1',
    name: '색상',
    rawValues: '',
    values: [{ id: 'black', label: '블랙' }],
  },
]

test('재고 추천 입력에 상품명·설명·옵션 그룹을 더해 근거 설명을 요청한다', () => {
  expect(
    toStockExplanationRequest(
      {
        categoryId: '8',
        name: ' 고양이 키링 ',
        description: '아크릴',
        [`${BLACK}:price`]: '12000',
        [`${BLACK}:stock`]: '60',
      },
      groups,
    ),
  ).toEqual({
    categoryId: 8,
    options: [{ unitPrice: 12000, quantity: 60 }],
    name: '고양이 키링',
    description: '아크릴',
    optionGroups: [{ name: '색상', values: ['블랙'] }],
  })
  expect(toStockExplanationRequest({ categoryId: '8' }, groups)).toBeNull()
})

test('표본이 부족해 추천하지 못했으면 버튼이 그 이유를 묻는다', () => {
  const recommendation = (dataGaps: string[]) =>
    ({ dataGaps }) as unknown as StockRecommendation
  expect(explanationButtonLabel(recommendation(['NO_SALES_SAMPLE']))).toBe(
    '추천하지 못한 이유',
  )
  expect(explanationButtonLabel(recommendation(['SMALL_SALES_SAMPLE']))).toBe(
    '근거 보기',
  )
})
