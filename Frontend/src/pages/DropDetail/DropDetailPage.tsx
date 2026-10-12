import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import DropDetailImages from '../../features/drop/DropDetailImages'
import DropGallery from '../../features/drop/DropGallery'
import { findMockDetailDrop } from '../../features/drop/mock/mockDetailDrops'
import { detailImages } from '../../features/drop/mock/mockDetailImages'
import { findMatchingSku, selectionLabel } from '../../features/drop/option'
import { minPrice, stock } from '../../features/drop/selectors'
import type { SharedProps } from '../../types/store'
import { dateLabel, daysUntil } from '../../utils/date'
import { won } from '../../utils/price'
import NotFoundPage from '../NotFound/NotFoundPage'

function generateOrderId() {
  return `GR-${Date.now().toString().slice(-8)}`
}

export default function DropDetailPage({
  authenticated,
  wishes,
  toggleWish,
  addOrder,
  notify,
}: SharedProps) {
  const { dropId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const drop = findMockDetailDrop(Number(dropId))
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [quantity, setQuantity] = useState(1)

  if (!drop || drop.status === 'DRAFT') return <NotFoundPage />

  const selectedSku = findMatchingSku(drop.skus, selected)
  const wished = wishes.has(drop.id)
  const soldOut = stock(drop) === 0
  const maxQuantity = Math.min(5, selectedSku?.stock ?? 1)
  const orderable =
    drop.status === 'GRAB' &&
    selectedSku &&
    selectedSku.stock > 0 &&
    quantity >= 1 &&
    quantity <= maxQuantity
  const optionLabel = selectedSku
    ? selectionLabel(drop.optionGroups, selectedSku.selections) || '기본 옵션'
    : ''

  const order = (event: FormEvent) => {
    event.preventDefault()
    if (!selectedSku || !orderable) return
    if (!authenticated) {
      notify('로그인 후 주문할 수 있어요.')
      navigate('/login', { state: { from: location.pathname } })
      return
    }
    addOrder({
      id: generateOrderId(),
      drop,
      optionLabel,
      quantity,
      total: selectedSku.price * quantity + drop.shippingFee,
    })
    notify('데모 주문을 MY에 담았어요. 실제 주문·결제는 진행되지 않습니다.')
    navigate('/my')
  }

  return (
    <>
      <div className="breadcrumbs">
        <Link to={drop.status === 'WISH' ? '/wish' : '/grab'}>← 목록으로</Link>
        <span>/</span>
        <span>{drop.category}</span>
      </div>
      <section className="detail-layout">
        <DropGallery key={drop.id} drop={drop} />
        <div className="detail-summary">
          <div className="detail-status">
            <b>
              {drop.status === 'WISH'
                ? `오픈 D−${daysUntil(drop.saleStartsAt)}`
                : drop.status === 'GRAB'
                  ? 'LIMITED DROP'
                  : drop.status === 'ENDED'
                    ? '판매 종료'
                    : '판매 취소'}
            </b>
            <span>
              {drop.status === 'WISH'
                ? `${drop.wishCount} WISH`
                : drop.status === 'GRAB'
                  ? `재고 ${stock(drop)}개`
                  : ''}
            </span>
          </div>
          <p className="creator">{drop.brand}</p>
          <h1>{drop.name}</h1>
          <p className="detail-description">{drop.description}</p>
          <p className="detail-price">
            {won(selectedSku?.price ?? minPrice(drop))}
            <small>{selectedSku ? '선택 옵션 가격' : '최저가'}</small>
          </p>

          <dl className="meta-list">
            <div>
              <dt>판매 기간</dt>
              <dd>
                {dateLabel(drop.saleStartsAt)} – {dateLabel(drop.saleEndsAt)}
              </dd>
            </div>
            <div>
              <dt>배송</dt>
              <dd>
                {won(drop.shippingFee)} · {drop.shippingNotice}
              </dd>
            </div>
          </dl>

          {drop.status === 'WISH' ? (
            <div className="wish-panel">
              <strong>
                {drop.optionGroups.map((group) => group.name).join(' · ') ||
                  '기본'}{' '}
                옵션으로 공개될 예정이에요.
              </strong>
              <p>
                WISH는 구매, 재고 예약 또는 구매 우선권을 보장하지 않습니다.
              </p>
              <button
                type="button"
                className="primary-button full"
                onClick={() => {
                  if (toggleWish(drop.id))
                    notify(wished ? 'WISH를 취소했어요.' : 'WISH에 담았어요.')
                }}
              >
                {wished ? '✓ WISH 취소하기' : '♡ WISH 등록하기'}
              </button>
            </div>
          ) : drop.status === 'GRAB' ? (
            <form className="buy-form" onSubmit={order}>
              {drop.optionGroups.map((group) => (
                <label key={group.id}>
                  <span>{group.name}</span>
                  <select
                    required
                    value={selected[group.id] ?? ''}
                    onChange={(event) => {
                      setSelected((current) => ({
                        ...current,
                        [group.id]: event.target.value,
                      }))
                      setQuantity(1)
                    }}
                  >
                    <option value="">선택해주세요</option>
                    {group.values.map((value) => (
                      <option key={value.id} value={value.id}>
                        {value.label}
                      </option>
                    ))}
                  </select>
                </label>
              ))}
              {drop.optionGroups.length === 0 && (
                <p className="default-option">
                  별도 선택이 없는 기본 옵션 상품입니다.
                </p>
              )}
              <label>
                <span>수량</span>
                <input
                  type="number"
                  min="1"
                  max={maxQuantity}
                  value={quantity}
                  onChange={(event) =>
                    setQuantity(Math.max(1, Number(event.target.value)))
                  }
                />
              </label>
              <div className={`sku-result ${selectedSku ? '' : 'muted'}`}>
                {selectedSku ? (
                  <>
                    <span>{optionLabel}</span>
                    <strong>
                      {selectedSku.stock
                        ? `재고 ${selectedSku.stock}개`
                        : '품절'}
                    </strong>
                  </>
                ) : (
                  <span>
                    모든 옵션을 선택하면 재고와 가격을 확인할 수 있어요.
                  </span>
                )}
              </div>
              <div className="total-row">
                <span>상품 합계</span>
                <strong>
                  {won((selectedSku?.price ?? minPrice(drop)) * quantity)}
                </strong>
              </div>
              <button
                type="submit"
                className="primary-button full"
                disabled={!orderable}
              >
                {soldOut
                  ? '전체 품절'
                  : orderable
                    ? authenticated
                      ? '데모 GRAB 하기 ↗'
                      : '로그인 후 GRAB ↗'
                    : selectedSku?.stock === 0
                      ? '선택 옵션 품절'
                      : quantity > maxQuantity
                        ? '수량을 확인해주세요'
                        : '옵션을 선택해주세요'}
              </button>
            </form>
          ) : (
            <div className="wish-panel">
              <strong>
                {drop.status === 'ENDED'
                  ? '이 DROP은 판매가 종료됐어요.'
                  : '이 DROP은 판매가 취소됐어요.'}
              </strong>
              <p>상품 정보는 계속 확인할 수 있어요.</p>
            </div>
          )}
        </div>
      </section>

      <section className="story-section">
        <h2>상품 상세 설명</h2>
        <p>{drop.description}</p>
        <DropDetailImages images={detailImages(drop.id)} />
      </section>
      <section className="detail-shipping panel">
        <h2>배송 안내</h2>
        <p>배송비 {won(drop.shippingFee)}</p>
        <p>{drop.shippingNotice}</p>
      </section>
    </>
  )
}
