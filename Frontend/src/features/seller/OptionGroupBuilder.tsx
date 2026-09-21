import { useMemo, useState } from 'react'
import { optionCombinations, selectionLabel } from '../drop/option'
import type { OptionGroup } from '../../types/drop'

type DraftGroup = OptionGroup & { rawValues: string }

export default function OptionGroupBuilder() {
  const [groups, setGroups] = useState<DraftGroup[]>([
    {
      id: 'group-1',
      name: '소재',
      rawValues: '코튼, 린넨',
      values: [
        { id: 'value-1', label: '코튼' },
        { id: 'value-2', label: '린넨' },
      ],
    },
  ])
  const parsedGroups = useMemo<OptionGroup[]>(
    () => groups.filter((group) => group.name.trim() && group.values.length),
    [groups],
  )
  const combinations = optionCombinations(parsedGroups)

  const updateGroup = (
    index: number,
    field: 'name' | 'rawValues',
    value: string,
  ) => {
    setGroups((current) =>
      current.map((group, itemIndex) => {
        if (itemIndex !== index) return group
        if (field === 'name') return { ...group, name: value }
        return {
          ...group,
          rawValues: value,
          values: value
            .split(',')
            .map((item) => item.trim())
            .filter(Boolean)
            .map((label, valueIndex) => ({
              id: `${group.id}-value-${valueIndex}`,
              label,
            })),
        }
      }),
    )
  }

  return (
    <>
      <div className="form-title-row">
        <div>
          <h2>옵션 구성</h2>
          <p>색상·사이즈에 한정하지 않고 필요한 기준을 직접 만듭니다.</p>
        </div>
        <button
          type="button"
          className="secondary-button"
          onClick={() =>
            setGroups((current) => [
              ...current,
              {
                id: `group-${Date.now()}`,
                name: '',
                rawValues: '',
                values: [],
              },
            ])
          }
        >
          ＋ 그룹 추가
        </button>
      </div>
      <div className="option-builder">
        {groups.map((group, index) => (
          <div className="option-group" key={group.id}>
            <span>그룹 {index + 1}</span>
            <label>
              그룹명
              <input
                required
                value={group.name}
                onChange={(event) =>
                  updateGroup(index, 'name', event.target.value)
                }
                placeholder="예: 소재, 길이, 포장"
              />
            </label>
            <label>
              선택값
              <input
                required
                value={group.rawValues}
                onChange={(event) =>
                  updateGroup(index, 'rawValues', event.target.value)
                }
                placeholder="쉼표로 구분: 코튼, 린넨"
              />
            </label>
            {groups.length > 1 && (
              <button
                type="button"
                className="remove-button"
                onClick={() =>
                  setGroups((current) =>
                    current.filter((_, itemIndex) => itemIndex !== index),
                  )
                }
              >
                삭제
              </button>
            )}
          </div>
        ))}
      </div>
      <div className="sku-table">
        <div className="sku-heading">
          <strong>판매 조합(SKU)</strong>
          <span>{combinations.length}개 조합</span>
        </div>
        {combinations.slice(0, 12).map((combination) => (
          <div className="sku-row" key={JSON.stringify(combination)}>
            <span>
              {selectionLabel(parsedGroups, combination) ||
                '선택값을 입력해주세요'}
            </span>
            <label>
              가격
              <input
                required
                type="number"
                min="0"
                step="100"
                defaultValue="29000"
              />
            </label>
            <label>
              재고
              <input required type="number" min="0" defaultValue="10" />
            </label>
          </div>
        ))}
        {combinations.length > 12 && (
          <p className="form-hint">
            초안에서는 처음 12개 조합만 표시합니다. 옵션 값을 줄여주세요.
          </p>
        )}
      </div>
    </>
  )
}
