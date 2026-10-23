import { describe, expect, it } from 'vitest'
import type { OptionGroup } from '../../../types/drop'
import {
  findMatchingSku,
  optionCombinations,
  optionStockLabel,
} from '../logic/option'

const groups: OptionGroup[] = [
  {
    id: 'material',
    name: '소재',
    values: [
      { id: 'cotton', label: '코튼' },
      { id: 'linen', label: '린넨' },
    ],
  },
  {
    id: 'length',
    name: '길이',
    values: [
      { id: 'short', label: '숏' },
      { id: 'long', label: '롱' },
    ],
  },
]

describe('판매자 정의 옵션', () => {
  it('그룹의 모든 값 조합을 만들고 일치하는 SKU를 찾는다', () => {
    expect(optionCombinations(groups)).toHaveLength(4)
    expect(
      findMatchingSku(
        [
          {
            id: 1,
            selections: { material: 'linen', length: 'long' },
            price: 39000,
            stock: 3,
          },
        ],
        { material: 'linen', length: 'long' },
      )?.id,
    ).toBe(1)
  })
})

describe('상세 옵션별 재고 표시', () => {
  const skus = [
    { id: 1, selections: { fit: '1', sleeve: '1' }, price: 100 },
    { id: 2, selections: { fit: '1', sleeve: '2' }, price: 100 },
    { id: 3, selections: { fit: '2', sleeve: '1' }, price: 100 },
  ]
  const stocks = new Map([
    [1, 8],
    [2, 3],
    [3, 0],
  ])
  it('다른 그룹 선택과 호환되는 조합의 재조회 재고만 합산한다', () => {
    expect(optionStockLabel(skus, {}, 'fit', '1', stocks)).toBe('11개 남음')
    expect(
      optionStockLabel(skus, { fit: '2', sleeve: '2' }, 'fit', '1', stocks),
    ).toBe('3개 남음')
    expect(optionStockLabel(skus, { fit: '1' }, 'sleeve', '1', stocks)).toBe(
      '8개 남음',
    )
    expect(
      optionStockLabel(skus, { fit: '', sleeve: '' }, 'fit', '1', stocks),
    ).toBe('11개 남음')
  })
  it('품절·미확인·삭제된 조합을 구분한다', () => {
    expect(optionStockLabel(skus, {}, 'fit', '2', stocks)).toBe('품절')
    expect(optionStockLabel(skus, { sleeve: '2' }, 'fit', '2', stocks)).toBe(
      '선택 불가',
    )
    expect(optionStockLabel([], {}, 'fit', '1', stocks)).toBe('선택 불가')
    expect(
      optionStockLabel(
        skus,
        {},
        'fit',
        '1',
        new Map([
          [1, 0],
          [2, 0],
        ]),
      ),
    ).toBe('품절')
  })
  it('재고 응답 전이거나 응답에 없는 SKU가 섞이면 확인 중으로 표시한다', () => {
    expect(optionStockLabel(skus, {}, 'fit', '1', new Map())).toBe(
      '재고 확인 중',
    )
    expect(optionStockLabel(skus, {}, 'fit', '1', new Map([[1, 8]]))).toBe(
      '재고 확인 중',
    )
  })
})
