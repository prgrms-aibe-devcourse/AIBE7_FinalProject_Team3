const MAX_SUBSCRIPTION_IDS = 100

export function normalizeDropIds(dropIds: number | readonly number[]) {
  const values = typeof dropIds === 'number' ? [dropIds] : [...dropIds]
  if (values.length === 0 || values.length > MAX_SUBSCRIPTION_IDS)
    throw new RangeError('구독할 DROP ID가 올바르지 않습니다.')
  const unique = new Set<number>()
  for (const dropId of values) {
    if (!Number.isSafeInteger(dropId) || dropId <= 0)
      throw new RangeError('구독할 DROP ID가 올바르지 않습니다.')
    unique.add(dropId)
  }
  return [...unique].sort((a, b) => a - b)
}

export function openDropStockStream(dropIds: number | readonly number[]) {
  const normalized = normalizeDropIds(dropIds)
  return new EventSource(
    `/api/v1/drops/stock-stream?dropIds=${normalized.join(',')}`,
    { withCredentials: true },
  )
}
