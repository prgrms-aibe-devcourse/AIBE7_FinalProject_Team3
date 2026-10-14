import type { components } from '../../../api/schema/drop'
import type { CardDrop } from '../../../types/drop'

type PublicDropList = components['schemas']['PublicDropListResponse']

function toCardDrop(id: number, drop: PublicDropList): CardDrop {
  return {
    id,
    name: drop.name ?? '',
    image: drop.thumbnailUrl ?? null,
    status: drop.status ?? 'GRAB',
    wishCount: drop.wishCount ?? 0,
    saleStartsAt: drop.saleStartsAt ?? '',
    saleEndsAt: drop.saleEndsAt ?? '',
    brand: drop.brandName,
    minPrice: drop.minPrice,
    availableStock: drop.availableStock,
    soldOut: drop.soldOut,
  }
}

// 식별자 없는 항목은 카드로 만들 수 없으므로 제외한다. 서버가 순서·페이지를 정한다.
export function toCardDrops(drops: readonly PublicDropList[]): CardDrop[] {
  return drops.flatMap((drop) =>
    drop.dropId === undefined ? [] : [toCardDrop(drop.dropId, drop)],
  )
}
