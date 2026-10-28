import { toCardDrops } from '../logic/dropView'
import { relatedTitle } from '../logic/relatedDrops'
import { useRelatedDrops } from '../logic/useDrops'
import DropCard from './DropCard'

/*
    관련 DROP 추천(GR-195). WISH 중인 상세는 하단에, 품절·종료 상세는 품절 안내 아래에 보여 준다.
    판매 중인 상세에서는 부모가 그리지 않는다. 추천이 없거나 조회에 실패하면 영역을 숨긴다.
 */
export default function RelatedDrops({
  dropId,
  context,
  wishes,
  wishReady,
  onWish,
}: {
  dropId: number
  context: 'wish' | 'closed'
  wishes: ReadonlySet<number>
  wishReady: boolean
  onWish: (id: number) => void
}) {
  const query = useRelatedDrops(dropId)
  const data = query.data
  const title = relatedTitle(context, data?.basis)
  if (!data || !title || !data.drops?.length) return null
  return (
    <section className="related-drops" aria-labelledby="related-drops-title">
      <h2 id="related-drops-title">{title}</h2>
      <div className="product-grid related-drops-grid">
        {toCardDrops(data.drops).map((drop) => (
          <DropCard
            key={drop.id}
            drop={drop}
            mode="WISH"
            wished={wishes.has(drop.id)}
            wishReady={wishReady}
            onWish={onWish}
          />
        ))}
      </div>
    </section>
  )
}
