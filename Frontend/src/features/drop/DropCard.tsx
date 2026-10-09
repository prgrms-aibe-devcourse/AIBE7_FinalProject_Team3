import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import type { Drop } from '../../types/drop'
import { daysUntil, dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

type Props = {
  drop: Drop
  wished: boolean
  onWish: (id: number) => void
  mode?: 'WISH' | 'GRAB'
}

export default function DropCard({
  drop,
  wished,
  onWish,
  mode = drop.status === 'WISH' ? 'WISH' : 'GRAB',
}: Props) {
  const [failedImage, setFailedImage] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const remaining = new Date(drop.saleStartsAt).getTime() - now
    if (remaining <= 0) return
    const timeout = window.setTimeout(
      () => setNow(Date.now()),
      Math.min(remaining, 2_147_483_647),
    )
    return () => window.clearTimeout(timeout)
  }, [drop.saleStartsAt, now])
  const activeSkus = drop.skus.filter((sku) => sku.active !== false)
  const available = activeSkus.reduce((sum, sku) => sum + sku.stock, 0)
  const price = activeSkus.length
    ? Math.min(...activeSkus.map((sku) => sku.price))
    : null
  const wishMode = mode === 'WISH'
  const wishable = wishMode && now < new Date(drop.saleStartsAt).getTime()

  return (
    <article className="product-card">
      <Link className="product-visual" to={`/drops/${drop.id}`}>
        {drop.image && failedImage !== drop.image ? (
          <img
            src={drop.image}
            alt={drop.name}
            onError={() => setFailedImage(drop.image)}
          />
        ) : (
          <span
            className="product-image-placeholder"
            role="img"
            aria-label={`${drop.name} 이미지 없음`}
          >
            상품 이미지 준비 중
          </span>
        )}
        <span className={`badge ${wishMode ? 'lime' : ''}`}>
          {wishMode
            ? wishable
              ? `OPEN D−${daysUntil(drop.saleStartsAt)}`
              : '판매 시작'
            : available
              ? 'LIMITED DROP'
              : 'SOLD OUT'}
        </span>
        <span className="round-arrow" aria-hidden="true">
          ↗
        </span>
      </Link>
      <p className="creator">{drop.brand}</p>
      <h2>
        <Link to={`/drops/${drop.id}`}>{drop.name}</Link>
      </h2>
      <p className="price">
        {price === null ? (
          '판매 옵션 없음'
        ) : (
          <>
            {won(price)}
            <small>부터</small>
          </>
        )}
      </p>
      <p className="schedule">
        {dateLabel(wishMode ? drop.saleStartsAt : drop.saleEndsAt)}{' '}
        {wishMode ? '오픈' : '종료'}
      </p>
      <div className="card-bottom">
        <span>
          {wishMode ? (
            <>
              <b>{drop.wishCount + (wished ? 1 : 0)}</b> WISH
            </>
          ) : (
            <>
              남은 수량 <b>{available}개</b>
            </>
          )}
        </span>
        {wishMode ? (
          <button
            type="button"
            className={`secondary-button wish-button ${wished ? 'selected' : ''}`}
            aria-pressed={wished}
            disabled={!wishable}
            onClick={() => onWish(drop.id)}
          >
            {!wishable ? 'WISH 마감' : wished ? '✓ WISHED' : '♡ WISH'}
          </button>
        ) : (
          <Link
            className="secondary-button wish-button"
            to={`/drops/${drop.id}`}
          >
            {available ? 'GRAB ↗' : '품절 · 상세 보기 ↗'}
          </Link>
        )}
      </div>
    </article>
  )
}
