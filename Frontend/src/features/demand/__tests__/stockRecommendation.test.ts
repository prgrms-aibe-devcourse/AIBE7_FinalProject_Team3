import { expect, test } from 'vitest'
import type { StockRecommendation } from '../../../api/demand/stockRecommendation'
import type { OptionAllocation } from '../../../api/demand/stockRecommendation'
import {
  applyAllocatedStock,
  applyRecommendedStock,
  toOptionAllocationInput,
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

test('상품명과 설명이 모두 있으면 비슷한 상품 표본용으로 옵션 구성과 함께 보낸다', () => {
  const values = {
    categoryId: '3',
    name: ' 오버핏 티셔츠 ',
    description: '부드러운 코튼',
    [`${BLACK}:price`]: '59000',
  }
  const groups = [
    {
      id: 'g1',
      name: '색상',
      values: [
        { id: 'black', label: '블랙' },
        { id: 'blank', label: ' ' },
      ],
    },
    { id: 'g2', name: ' ', values: [{ id: 'x', label: 'X' }] },
  ]

  expect(toRecommendationInput(values, groups)?.request).toEqual({
    categoryId: 3,
    options: [{ unitPrice: 59000 }],
    name: '오버핏 티셔츠',
    description: '부드러운 코튼',
    optionGroups: [{ name: '색상', values: ['블랙'] }],
  })
  // 설명이 없으면 상품명도 보내지 않는다(카테고리 기준)
  expect(
    toRecommendationInput({ ...values, description: '' }, groups)?.request,
  ).toEqual({ categoryId: 3, options: [{ unitPrice: 59000 }] })
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

const COLOR = {
  id: 'g1',
  name: '색상',
  values: [
    { id: 'black', label: '블랙' },
    { id: 'white', label: ' 화이트 ' },
  ],
}

test('옵션별 배분 요청은 SKU 키의 선택을 옵션 구성 순서대로 값으로 바꿔 붙인다', () => {
  const input = toOptionAllocationInput(
    {
      categoryId: '3',
      name: '티셔츠',
      description: '코튼',
      [`${BLACK}:price`]: '39000',
      [`${WHITE}:price`]: '39000',
      [`${WHITE}:stock`]: '5',
    },
    [COLOR],
  )

  expect(input).toEqual({
    request: {
      categoryId: 3,
      name: '티셔츠',
      description: '코튼',
      optionGroups: [{ name: '색상', values: ['블랙', '화이트'] }],
      options: [
        { unitPrice: 39000, values: ['블랙'] },
        { unitPrice: 39000, quantity: 5, values: ['화이트'] },
      ],
    },
    rowKeys: [BLACK, WHITE],
  })
})

test('상품명·설명·옵션 구성이 없거나 빈 그룹이 있으면 옵션별 배분을 요청하지 않는다', () => {
  const values = {
    categoryId: '3',
    name: '티셔츠',
    description: '코튼',
    [`${BLACK}:price`]: '39000',
  }
  expect(
    toOptionAllocationInput({ ...values, description: '' }, [COLOR]),
  ).toBeNull()
  expect(toOptionAllocationInput(values, [])).toBeNull()
  expect(
    toOptionAllocationInput(values, [
      COLOR,
      { id: 'g2', name: ' ', values: [] },
    ]),
  ).toBeNull()
})

test('옵션별 배분 수량을 각 SKU 재고 칸에 채우고 수량이 없으면 그대로 둔다', () => {
  const allocation = {
    options: [
      { index: 0, recommendedQuantity: 33 },
      { index: 1, recommendedQuantity: null },
    ],
  } as unknown as OptionAllocation

  expect(
    applyAllocatedStock(
      { [`${WHITE}:stock`]: '7' },
      [BLACK, WHITE],
      allocation,
    ),
  ).toEqual({ [`${BLACK}:stock`]: '33', [`${WHITE}:stock`]: '7' })
})
