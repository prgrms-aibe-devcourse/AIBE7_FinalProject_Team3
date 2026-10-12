import type { Drop } from '../../types/drop'

export const minPrice = (drop: Drop) =>
  Math.min(...drop.skus.map((sku) => sku.price))
export const stock = (drop: Drop) =>
  drop.skus.reduce((sum, sku) => sum + sku.stock, 0)

const DAY = 86_400_000

// 임박 판정 기준과 eventType은 백엔드(SELLER.md 2.3, UpcomingDropEventType)와 동일하다.
// WISH는 saleStartsAt(시작 임박), GRAB은 saleEndsAt(종료 임박). 이미 지난 일정은 제외.
export const upcoming = (drop: Drop, now = Date.now()) => {
  const at =
    drop.status === 'WISH'
      ? drop.saleStartsAt
      : drop.status === 'GRAB'
        ? drop.saleEndsAt
        : null
  if (!at) return null
  const left = new Date(at).getTime() - now
  if (left <= 0 || left > DAY) return null
  const hours = Math.floor(left / 3_600_000)
  const eventType = drop.status === 'WISH' ? 'START' : 'END'
  return {
    eventType,
    label: eventType === 'START' ? '시작 임박' : '종료 임박',
    remain:
      hours > 0
        ? `${hours}시간 후`
        : `${Math.max(1, Math.round(left / 60_000))}분 후`,
  }
}
