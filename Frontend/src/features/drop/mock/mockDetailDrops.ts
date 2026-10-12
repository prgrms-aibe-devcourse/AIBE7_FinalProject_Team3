import type { Drop } from '../../../types/drop'
import { drops } from './mockDrops'

const sample = drops.find((drop) => drop.id === 203)!

const examples: Drop[] = [
  {
    ...sample,
    id: 204,
    name: '에브리데이 캔버스 백 · 품절',
    skus: sample.skus.map((sku) => ({ ...sku, stock: 0 })),
  },
  {
    ...sample,
    id: 301,
    name: '에브리데이 캔버스 백 · 판매 종료',
    status: 'ENDED',
    saleStartsAt: new Date(Date.now() - 10 * 86_400_000).toISOString(),
    saleEndsAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
  },
]

export function findMockDetailDrop(id: number): Drop | undefined {
  return (
    drops.find((drop) => drop.id === id) ??
    examples.find((drop) => drop.id === id)
  )
}
