import { expect, test } from 'vitest'
import type { StockRecommendation } from '../../../api/demand/stockRecommendation'
import {
  applyRecommendedStock,
  toRecommendationInput,
} from '../logic/stockRecommendation'

const BLACK = 'sku:{"g1":"black"}'
const WHITE = 'sku:{"g1":"white"}'
const RED = 'sku:{"g1":"red"}'

test('카테고리와 가격이 정해진 활성 SKU만 순서대로 요청에 넣고 입력 재고는 함께 보낸다', () => {
  const input = toRecommendationInput({
    categoryId: '3',
    [`${BLACK}:price`]: '59000',
    [`${BLACK}:stock`]: '40',
    [`${WHITE}:price`]: '59000',
    [`${WHITE}:stock`]: '',
    [`${RED}:price`]: '59000',
    [`${RED}:active`]: 'false',
  })

  expect(input).toEqual({
    request: {
      categoryId: 3,
      options: [{ unitPrice: 59000, quantity: 40 }, { unitPrice: 59000 }],
    },
    rowKeys: [BLACK, WHITE],
  })
})

test('카테고리가 없거나 가격을 정한 SKU가 없으면 요청하지 않는다', () => {
  expect(toRecommendationInput({ [`${BLACK}:price`]: '1000' })).toBeNull()
  expect(
    toRecommendationInput({ categoryId: '3', [`${BLACK}:price`]: '' }),
  ).toBeNull()
  expect(
    toRecommendationInput({ categoryId: '3', [`${BLACK}:price`]: '-1' }),
  ).toBeNull()
})

test('추천 배분을 요청 순서대로 각 SKU 재고 칸에 채우고 다른 값은 그대로 둔다', () => {
  const values = {
    name: '상품',
    [`${BLACK}:price`]: '59000',
    [`${BLACK}:stock`]: '40',
    [`${WHITE}:price`]: '59000',
  }
  const recommendation = {
    recommendation: {
      options: [
        { index: 0, recommendedQuantity: 30 },
        { index: 1, recommendedQuantity: 14 },
      ],
    },
  } as StockRecommendation

  expect(applyRecommendedStock(values, [BLACK, WHITE], recommendation)).toEqual(
    {
      ...values,
      [`${BLACK}:stock`]: '30',
      [`${WHITE}:stock`]: '14',
    },
  )
  expect(
    applyRecommendedStock(values, [BLACK], {
      recommendation: { options: [{ index: 0, recommendedQuantity: null }] },
    } as unknown as StockRecommendation),
  ).toEqual(values)
})
