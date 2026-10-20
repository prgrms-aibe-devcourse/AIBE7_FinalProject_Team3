import { type Dispatch, type SetStateAction } from 'react'
import { selectionLabel } from '../drop/logic/option'
import DropFieldErrors from './components/DropFieldErrors'
import type { DraftGroup } from './logic/sellerDropForm'
import type { DropFieldError } from './logic/sellerDropErrors'

export default function OptionGroupBuilder({
  groups,
  onGroupsChange,
  values,
  setValues,
  errors,
}: {
  groups: DraftGroup[]
  onGroupsChange: (groups: DraftGroup[]) => void
  values: Record<string, string>
  setValues: Dispatch<SetStateAction<Record<string, string>>>
  errors: DropFieldError[]
}) {
  let requestIndex = 0
  const rows = Object.keys(values)
    .filter((key) => key.startsWith('sku:') && key.endsWith(':price'))
    .map((key) => {
      const rowKey = key.slice(0, -6)
      const empty = !values[key]?.trim() && !values[`${rowKey}:stock`]?.trim()
      return {
        key: rowKey,
        selections: JSON.parse(key.slice(4, -6)) as Record<string, string>,
        requestIndex: empty ? null : requestIndex++,
      }
    })
  const updateGroup = (
    index: number,
    field: 'name' | 'rawValues',
    value: string,
  ) => {
    onGroupsChange(
      groups.map((group, itemIndex) => {
        if (itemIndex !== index) return group
        if (field === 'name') return { ...group, name: value }
        return {
          ...group,
          rawValues: value,
          values: value
            .split(',')
            .map((item) => item.trim())
            .filter(Boolean)
            .map((label) => ({
              id:
                group.values.find((item) => item.label === label)?.id ??
                crypto.randomUUID(),
              label,
            })),
        }
      }),
    )
  }
  const setValue = (key: string, value: string) =>
    setValues((current) => ({ ...current, [key]: value }))

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
            onGroupsChange([
              ...groups,
              { id: crypto.randomUUID(), name: '', rawValues: '', values: [] },
            ])
          }
        >
          ＋ 그룹 추가
        </button>
      </div>
      <div className="option-builder">
        {groups.map((group, index) => (
          <div key={group.id}>
            <div className="option-group">
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
              <button
                type="button"
                className="remove-button"
                onClick={() =>
                  onGroupsChange(
                    groups.filter((_, itemIndex) => itemIndex !== index),
                  )
                }
              >
                삭제
              </button>
            </div>
            <DropFieldErrors field={`optionGroups[${index}]`} errors={errors} />
          </div>
        ))}
      </div>
      <div className="sku-table">
        <div className="sku-heading">
          <strong>판매 조합(SKU)</strong>
          <span>{rows.length}개 조합</span>
        </div>
        {rows.map(({ key, selections, requestIndex }) => (
          <div key={key}>
            <div className="sku-row">
              <span>{selectionLabel(groups, selections) || '기본 옵션'}</span>
              <label>
                가격
                <input
                  required
                  name={`${key}:price`}
                  type="number"
                  min="0"
                  step="1"
                  value={values[`${key}:price`] ?? ''}
                  onChange={(event) =>
                    setValue(`${key}:price`, event.target.value)
                  }
                />
              </label>
              <label>
                재고
                <input
                  required
                  name={`${key}:stock`}
                  type="number"
                  min="0"
                  step="1"
                  value={values[`${key}:stock`] ?? ''}
                  onChange={(event) =>
                    setValue(`${key}:stock`, event.target.value)
                  }
                />
              </label>
              <label className="sku-active">
                <input
                  type="checkbox"
                  checked={values[`${key}:active`] !== 'false'}
                  onChange={(event) =>
                    setValue(`${key}:active`, String(event.target.checked))
                  }
                />
                판매 활성
              </label>
            </div>
            {requestIndex !== null && (
              <DropFieldErrors
                field={`options[${requestIndex}]`}
                errors={errors}
              />
            )}
            <DropFieldErrors field={`${key}:price`} errors={errors} />
            <DropFieldErrors field={`${key}:stock`} errors={errors} />
          </div>
        ))}
        <DropFieldErrors
          field="options"
          errors={errors.filter((error) => error.field === 'options')}
        />
        <DropFieldErrors
          field="optionGroups"
          errors={errors.filter((error) => error.field === 'optionGroups')}
        />
      </div>
    </>
  )
}
