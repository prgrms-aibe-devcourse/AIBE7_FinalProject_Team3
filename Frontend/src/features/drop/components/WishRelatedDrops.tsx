import { Link } from 'react-router-dom'
import { dateLabel } from '../../../utils/date'
import { relatedTitle } from '../logic/relatedDrops'
import { useRelatedDrops } from '../logic/useDrops'

/*
    마이페이지 WISH 목록에서 판매 종료·취소된 DROP의 비슷한 굿즈(GR-195, DROP.md 1.4).
    펼쳤을 때만 그려져 그때 한 번 조회한다. 품절·종료 상세와 같은 문구를 쓰고, 카드 대신 이름·오픈 일정만 보여 준다.
 */
export default function WishRelatedDrops({
  dropId,
  id,
}: {
  dropId: number
  id: string
}) {
  const query = useRelatedDrops(dropId)
  const data = query.data
  const title = relatedTitle('closed', data?.basis)
  return (
    <div className="wish-related" id={id}>
      {query.isPending ? (
        <p role="status">비슷한 굿즈를 찾는 중이에요.</p>
      ) : query.isError ? (
        <p role="alert">
          비슷한 굿즈를 불러오지 못했어요.{' '}
          <button type="button" onClick={() => void query.refetch()}>
            다시 시도
          </button>
        </p>
      ) : !title || !data?.drops?.length ? (
        <p>지금 WISH할 수 있는 비슷한 굿즈가 없어요.</p>
      ) : (
        <>
          <p className="wish-related-title">{title}</p>
          <ul>
            {data.drops.map((drop) => (
              <li key={drop.dropId}>
                <Link to={`/drops/${drop.dropId}`}>{drop.name}</Link>
                <small>
                  오픈 ·{' '}
                  {drop.saleStartsAt ? dateLabel(drop.saleStartsAt) : '미정'}
                </small>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
