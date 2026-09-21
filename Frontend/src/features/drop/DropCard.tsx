import { Link } from 'react-router-dom'
import type { Drop } from '../../types/drop'
import { daysUntil, dateLabel } from '../../utils/date'
import { won } from '../../utils/price'
import { minPrice, stock } from './selectors'

type Props = {
  drop: Drop
  wished: boolean
  onWish: (id: number) => void
}

export default function DropCard({ drop, wished, onWish }: Props) {
  const available = stock(drop)

  return (
    <article className="product-card">
      <Link className="product-visual" to={`/drops/${drop.id}`}>
        <img src={drop.image} alt={drop.name} />
        <span className={`badge ${drop.status === 'WISH' ? 'lime' : ''}`}>
          {drop.status === 'WISH' ? `OPEN D−${daysUntil(drop.saleStartsAt)}` : available ? 'LIMITED DROP' : 'SOLD OUT'}
        </span>
        <span className="round-arrow" aria-hidden="true">↗</span>
      </Link>
      <p className="creator">{drop.brand}</p>
      <h2><Link to={`/drops/${drop.id}`}>{drop.name}</Link></h2>
      <p className="price">{won(minPrice(drop))}<small>부터</small></p>
      <p className="schedule">
        {dateLabel(drop.status === 'WISH' ? drop.saleStartsAt : drop.saleEndsAt)} {drop.status === 'WISH' ? '오픈' : '종료'}
      </p>
      <div className="card-bottom">
        <span>{drop.status === 'WISH' ? <><b>{drop.wishCount + (wished ? 1 : 0)}</b> WISH</> : <>남은 수량 <b>{available}개</b></>}</span>
        {drop.status === 'WISH' ? (
          <button className={`wish-button ${wished ? 'selected' : ''}`} onClick={() => onWish(drop.id)}>
            {wished ? '✓ WISHED' : '♡ WISH'}
          </button>
        ) : (
          <Link className="wish-button" to={`/drops/${drop.id}`}>GRAB ↗</Link>
        )}
      </div>
    </article>
  )
}
