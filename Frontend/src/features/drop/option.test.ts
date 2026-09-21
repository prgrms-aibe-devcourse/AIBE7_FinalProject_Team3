import { describe, expect, it } from 'vitest'
import type { OptionGroup } from '../../types/drop'
import { findMatchingSku, optionCombinations } from './option'

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
