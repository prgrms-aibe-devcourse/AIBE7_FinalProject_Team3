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
    { id: 1, selections: { fit: '1', sleeve: '1' }, price: 100, stock: 8 },
    { id: 2, selections: { fit: '1', sleeve: '2' }, price: 100, stock: 5 },
    { id: 3, selections: { fit: '2', sleeve: '1' }, price: 100, stock: 0 },
  ]
  it('다른 그룹 선택과 호환되는 조합만 합산하고 최신 재고를 우선한다', () => {
    const stocks = new Map([[2, 3]])
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
    expect(optionStockLabel(skus, {}, 'fit', '2', new Map())).toBe('품절')
    expect(optionStockLabel(skus, { sleeve: '2' }, 'fit', '2', new Map())).toBe(
      '선택 불가',
    )
    expect(optionStockLabel([], {}, 'fit', '1', new Map())).toBe('선택 불가')
    expect(
      optionStockLabel(
        [{ ...skus[1], stock: undefined }],
        {},
        'fit',
        '1',
        new Map(),
      ),
    ).toBe('재고 확인 중')
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
})
