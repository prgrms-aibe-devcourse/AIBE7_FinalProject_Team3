import type { DetailSku, OptionGroup } from '../../../types/drop'

export function findMatchingSku<
  T extends { selections: Record<string, string> },
>(skus: readonly T[], selected: Record<string, string>): T | undefined {
  return skus.find((sku) =>
    Object.entries(sku.selections).every(
      ([groupId, valueId]) => selected[groupId] === valueId,
    ),
  )
}

export function optionCombinations(
  groups: OptionGroup[],
): Record<string, string>[] {
  return groups.reduce<Record<string, string>[]>(
    (rows, group) => {
      if (!group.id || group.values.length === 0) return rows
      return rows.flatMap((row) =>
        group.values.map((value) => ({ ...row, [group.id]: value.id })),
      )
    },
    [{}],
  )
}

export function selectionLabel(
  groups: OptionGroup[],
  selections: Record<string, string>,
) {
  return groups
    .map((group) => {
      const value = group.values.find(
        (item) => item.id === selections[group.id],
      )
      return value ? `${group.name}: ${value.label}` : ''
    })
    .filter(Boolean)
    .join(' · ')
}

export function optionStockLabel(
  skus: readonly DetailSku[],
  selected: Record<string, string>,
  groupId: string,
  valueId: string,
  stockByOption: ReadonlyMap<number, number>,
) {
  const matching = skus.filter(
    (sku) =>
      sku.selections[groupId] === valueId &&
      Object.entries(selected).every(
        ([id, value]) =>
          id === groupId || !value || sku.selections[id] === value,
      ),
  )
  if (matching.length === 0) return '선택 불가'
  let total = 0
  for (const sku of matching) {
    const stock = stockByOption.get(sku.id)
    if (stock === undefined) return '재고 확인 중'
    total += stock
  }
  return total === 0 ? '품절' : `${total}개 남음`
}
