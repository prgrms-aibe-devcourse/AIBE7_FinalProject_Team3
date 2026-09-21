import type { OptionGroup, Sku } from '../../types/drop'

export function findMatchingSku(skus: Sku[], selected: Record<string, string>) {
  return skus.find((sku) =>
    Object.entries(sku.selections).every(([groupId, valueId]) => selected[groupId] === valueId),
  )
}

export function optionCombinations(groups: OptionGroup[]): Record<string, string>[] {
  return groups.reduce<Record<string, string>[]>((rows, group) => {
    if (!group.id || group.values.length === 0) return rows
    return rows.flatMap((row) => group.values.map((value) => ({ ...row, [group.id]: value.id })))
  }, [{}])
}

export function selectionLabel(groups: OptionGroup[], selections: Record<string, string>) {
  return groups
    .map((group) => {
      const value = group.values.find((item) => item.id === selections[group.id])
      return value ? `${group.name}: ${value.label}` : ''
    })
    .filter(Boolean)
    .join(' · ')
}
