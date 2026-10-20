import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import axios from 'axios'
import EmptyState from '../../components/EmptyState/EmptyState'
import DropDetailImages from '../../features/drop/components/DropDetailImages'
import DropGallery from '../../features/drop/components/DropGallery'
import {
  toDropDetail,
  toOrderDrop,
} from '../../features/drop/logic/dropDetailView'
import {
  findMatchingSku,
  selectionLabel,
} from '../../features/drop/logic/option'
import {
  useDropDetail,
  useDropStocks,
} from '../../features/drop/logic/useDrops'
import { useDropStockStream } from '../../features/drop/logic/useDropStockStream'
import type { SharedProps } from '../../types/store'
import { dateLabel, daysUntil } from '../../utils/date'
import { won } from '../../utils/price'
import NotFoundPage from '../NotFound/NotFoundPage'

function generateOrderId() {
  return `GR-${Date.now().toString().slice(-8)}`
}

export default function DropDetailPage({
  authenticated,
  authStatus,
  wishes,
  wishesReady,
  toggleWish,
  addOrder,
  notify,
}: SharedProps) {
  const { dropId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const validId =
    /^\d+$/.test(dropId ?? '') &&
    Number.isSafeInteger(Number(dropId)) &&
    Number(dropId) > 0
  const id = validId ? Number(dropId) : 0

  const detailQuery = useDropDetail(id)
  const stocksQuery = useDropStocks(id)
  const retry = useDropStockStream(id)
  const [selected, setSelected] = useState<Record<string, string>>({})
  const [quantity, setQuantity] = useState(1)

  if (!validId) return <NotFoundPage />
  if (detailQuery.isPending)
    return (
      <div role="status" aria-live="polite">
        <EmptyState title="상품 정보를 불러오는 중이에요." variant="block" />
      </div>
    )
  if (
    detailQuery.isError &&
    axios.isAxiosError(detailQuery.error) &&
    detailQuery.error.response?.status === 404
  )
    return <NotFoundPage />
  if (detailQuery.isError && !detailQuery.data) {
    return (
      <div className="catalog-error" role="alert">
        <EmptyState
          title="상품 정보를 표시하지 못했어요."
          description="잠시 후 다시 시도해주세요."
          variant="block"
        />
        <button type="button" className="secondary-button" onClick={retry}>
          다시 시도
        </button>
      </div>
    )
  }

  const refreshFailed = detailQuery.isError || stocksQuery.isError
  const refreshing = detailQuery.isFetching || stocksQuery.isFetching
  const detail = toDropDetail(detailQuery.data)
  const stockByOption = new Map<number, number>()
  for (const option of stocksQuery.data?.options ?? []) {
    if (option.optionId === undefined) continue
    if (option.availableStock !== undefined)
      stockByOption.set(option.optionId, option.availableStock)
  }
  const totalStock = stocksQuery.data?.options?.length
    ? stocksQuery.data.options.reduce(
        (sum, option) => sum + (option.availableStock ?? 0),
        0,
      )
    : undefined

  const selectedSku = findMatchingSku(detail.skus, selected)
  const wished = wishes.has(detail.id)
  const selectedStock = selectedSku
    ? (stockByOption.get(selectedSku.id) ?? selectedSku.stock)
    : undefined
  const maxQuantity = Math.min(
    5,
    selectedStock && selectedStock > 0 ? selectedStock : 1,
  )
  const orderable =
    detail.actions.orderable &&
    !!selectedSku &&
    (selectedStock ?? 0) > 0 &&
    quantity >= 1 &&
    quantity <= maxQuantity
  const optionLabel = selectedSku
    ? selectionLabel(detail.optionGroups, selectedSku.selections) || '기본 옵션'
    : ''
  const mode = detail.actions.orderable
    ? 'buy'
    : detail.actions.wishable || detail.actions.wishCancelable
      ? 'wish'
      : 'closed'
  const unitPrice = selectedSku?.price ?? detail.minPrice ?? 0
  const grabLabel = !detail.actions.orderable
    ? '판매 종료되었어요'
    : !selectedSku
      ? '옵션을 선택해주세요'
      : selectedStock === 0
        ? '선택 옵션 품절'
        : quantity > maxQuantity
          ? '수량을 확인해주세요'
          : authenticated
            ? '데모 GRAB 하기 ↗'
            : '로그인 후 GRAB ↗'

  const order = (event: FormEvent) => {
    event.preventDefault()
    if (!selectedSku || !orderable) return
    if (authStatus === 'loading') return
    if (!authenticated) {
      notify('로그인 후 주문할 수 있어요.')
      navigate('/login', { state: { from: location.pathname } })
      return
    }
    const orderId = generateOrderId()
    addOrder({
      id: orderId,
      drop: toOrderDrop(detail),
      optionLabel,
      quantity,
      total: unitPrice * quantity + detail.shippingFee,
    })
    notify('Mock 주문을 만들었어요. 배송지와 결제 결과를 확인해주세요.')
    navigate(`/checkout/${orderId}`)
  }

  return (
    <>
      <div className="breadcrumbs">
        <Link to={detail.status === 'WISH' ? '/wish' : '/grab'}>
          ← 목록으로
        </Link>
        <span>/</span>
        <span>{detail.category}</span>
      </div>
      {refreshFailed && (
        <p className="form-error detail-refresh-error" role="alert">
          최신 상품 정보를 불러오지 못했어요. 잠시 후 다시 시도해주세요.{' '}
          <button
            type="button"
            className="secondary-button"
            onClick={retry}
            disabled={refreshing}
          >
            다시 시도
          </button>
        </p>
      )}
      <section className="detail-layout">
        <DropGallery
          key={detail.id}
          name={detail.name}
          status={detail.status}
          images={detail.galleryImages}
        />
        <div className="detail-summary">
          <div className="detail-status">
            <b>
              {detail.status === 'WISH'
                ? `오픈 D−${daysUntil(detail.saleStartsAt)}`
                : detail.status === 'GRAB'
                  ? 'LIMITED DROP'
                  : detail.status === 'ENDED'
                    ? '판매 종료'
                    : '판매 취소'}
            </b>
            <span>
              {detail.status === 'WISH'
                ? `${detail.wishCount} WISH`
                : detail.status === 'GRAB' && totalStock !== undefined
                  ? `재고 ${totalStock}개`
                  : ''}
            </span>
          </div>
          {detail.brand && <p className="creator">{detail.brand}</p>}
          <h1>{detail.name}</h1>
          <p className="detail-description">{detail.description}</p>
          <p className="detail-price">
            {selectedSku
              ? won(selectedSku.price)
              : detail.minPrice !== null
                ? won(detail.minPrice)
                : '판매 옵션 없음'}
            {(selectedSku || detail.minPrice !== null) && (
              <small>{selectedSku ? '선택 옵션 가격' : '최저가'}</small>
            )}
          </p>

          <dl className="meta-list">
            <div>
              <dt>판매 기간</dt>
              <dd>
                {dateLabel(detail.saleStartsAt)} –{' '}
                {dateLabel(detail.saleEndsAt)}
              </dd>
            </div>
            <div>
              <dt>배송</dt>
              <dd>
                {won(detail.shippingFee)} · {detail.shippingNotice}
              </dd>
            </div>
          </dl>

          {mode === 'wish' ? (
            <div className="wish-panel">
              <strong>
                {detail.optionGroups.map((group) => group.name).join(' · ') ||
                  '기본'}{' '}
                옵션으로 공개될 예정이에요.
              </strong>
              <p>{detail.wishNotice}</p>
              <button
                type="button"
                className="primary-button full"
                disabled={!wishesReady}
                onClick={() => {
                  void toggleWish(detail.id)
                }}
              >
                {wished ? '✓ WISH 취소하기' : '♡ WISH 등록하기'}
              </button>
            </div>
          ) : mode === 'buy' ? (
            <form className="buy-form" onSubmit={order}>
              {detail.optionGroups.map((group) => (
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
              {detail.optionGroups.length === 0 && (
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
                      {selectedStock
                        ? `재고 ${selectedStock}개`
                        : selectedStock === 0
                          ? '품절'
                          : '재고 확인 중'}
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
                <strong>{won(unitPrice * quantity)}</strong>
              </div>
              <button
                type="submit"
                className="primary-button full"
                disabled={!orderable}
              >
                {grabLabel}
              </button>
            </form>
          ) : (
            <div className="wish-panel">
              <strong>
                {detail.status === 'CANCELED'
                  ? '이 DROP은 판매가 취소됐어요.'
                  : detail.soldOut
                    ? '이 DROP은 품절됐어요.'
                    : '이 DROP은 판매가 종료됐어요.'}
              </strong>
              <p>상품 정보는 계속 확인할 수 있어요.</p>
            </div>
          )}
        </div>
      </section>

      <section className="story-section">
        <h2>상품 상세 설명</h2>
        <p>{detail.description}</p>
        <DropDetailImages images={detail.detailImages} />
      </section>
      <section className="detail-shipping panel">
        <h2>배송 안내</h2>
        <p>배송비 {won(detail.shippingFee)}</p>
        <p>{detail.shippingNotice}</p>
      </section>
    </>
  )
}
