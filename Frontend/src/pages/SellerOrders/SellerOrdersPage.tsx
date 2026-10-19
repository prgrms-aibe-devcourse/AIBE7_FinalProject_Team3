import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import type { SellerOrderStatus } from '../../features/order/mock/mockSellerOrders'
import {
  changeSellerOrderParams,
  readSellerOrderQuery,
} from '../../features/order/logic/sellerOrderQuery'
import {
  useSellerOrder,
  useSellerOrders,
} from '../../features/order/logic/useSellerOrders'
import { sellerOrderErrorMessage } from '../../features/order/logic/sellerOrderState'
import SellerOrderDetail from '../../features/order/components/SellerOrderDetail'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

const SELLER_ORDERS_PAGE_SIZE = 2

const TABS: { key: SellerOrderStatus; label: string }[] = [
  { key: 'PAID', label: '결제 완료' },
  { key: 'PREPARING', label: '배송 준비' },
  { key: 'SHIPPED', label: '배송 중' },
  { key: 'DELIVERED', label: '배송 완료' },
]

export default function SellerOrdersPage({
  notify,
}: {
  notify: (message: string) => void
}) {
  const [params, setParams] = useSearchParams()
  const [openId, setOpenId] = useState<string | null>(null)

  let result: ReturnType<typeof readSellerOrderQuery> | null = null
  try {
    result = readSellerOrderQuery(params)
  } catch (error) {
    if (!(error instanceof RangeError)) throw error
  }
  const dropId = result?.dropId || ''
  const dropIdNum = dropId ? Number(dropId) : undefined

  const listQuery = useSellerOrders(
    {
      dropId: dropIdNum,
      orderStatus: result?.orderStatus,
      paymentStatus: result?.paymentStatus || undefined,
      page: result?.page,
      size: SELLER_ORDERS_PAGE_SIZE,
    },
    result !== null,
  )
  const detailQuery = useSellerOrder(openId)

  const selectedStatus = result?.orderStatus ?? 'PAID'
  const orders = listQuery.data?.content ?? []

  const setFilter = (key: 'orderStatus' | 'paymentStatus', value: string) => {
    setParams(changeSellerOrderParams(params, key, value))
    setOpenId(null)
  }

  const toggleDetail = (orderId: string) => {
    setOpenId((current) => (current === orderId ? null : orderId))
  }

  const detail = detailQuery.data && {
    items: (detailQuery.data.items ?? []).map((item) => ({
      productName: item.productName ?? '',
      optionName: item.optionName ?? '',
      unitPrice: item.unitPrice ?? 0,
      quantity: item.quantity ?? 0,
      subtotal: item.subtotal ?? 0,
    })),
    itemsAmount: detailQuery.data.itemsAmount ?? 0,
    shippingAmount: detailQuery.data.shippingAmount ?? 0,
    totalAmount: detailQuery.data.totalAmount ?? 0,
    shipping: detailQuery.data.shipping?.carrier
      ? {
          carrier: detailQuery.data.shipping.carrier,
          trackingNumber: detailQuery.data.shipping.trackingNumber ?? '',
          deliveredAt: detailQuery.data.shipping.deliveredAt ?? null,
        }
      : null,
  }

  return (
    <section className="page-section seller-page seller-orders-page">
      <div className="seller-title">
        <div>
          <p className="eyebrow">ORDER DESK</p>
          <h1>주문·배송</h1>
          <p>본인 DROP의 주문과 발송 작업을 확인합니다.</p>
          {dropId && (
            <p>
              DROP #{dropId} 주문 ·{' '}
              <Link className="text-link" to="/seller/orders">
                전체 주문 보기
              </Link>
            </p>
          )}
        </div>
      </div>
      <section className="panel seller-order-filters" aria-label="주문 필터">
        <div className="chips order-tabs">
          {TABS.map((item) => (
            <button
              key={item.key}
              type="button"
              className={selectedStatus === item.key ? 'active' : undefined}
              aria-pressed={selectedStatus === item.key}
              onClick={() => setFilter('orderStatus', item.key)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="seller-order-selects">
          <label>
            결제 상태 필터
            <select
              value={result?.paymentStatus ?? ''}
              onChange={(event) =>
                setFilter('paymentStatus', event.target.value)
              }
            >
              <option value="">전체 결제 상태</option>
              <option value="SUCCEEDED">결제 성공</option>
              <option value="UNKNOWN">결제 확인 중</option>
            </select>
          </label>
        </div>
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>주문 목록</h2>
        </div>
        {!result ? (
          <EmptyState
            title="조회 조건이 올바르지 않아요."
            link="/seller/orders"
            label="조건 초기화"
          />
        ) : listQuery.isLoading ? (
          <div role="status">
            <EmptyState title="주문 목록을 불러오는 중이에요." />
          </div>
        ) : listQuery.isError ? (
          <div role="alert" className="seller-order-error">
            <EmptyState title={sellerOrderErrorMessage(listQuery.error)} />
            <button
              type="button"
              className="secondary-button"
              onClick={() => listQuery.refetch()}
            >
              다시 시도
            </button>
          </div>
        ) : orders.length ? (
          <div className="order-list">
            {orders.map((order) => {
              const orderId = order.orderId ?? ''
              const open = openId === orderId
              return (
                <article className="order-row" key={orderId}>
                  <div className="order-main">
                    <strong>{order.productName}</strong>
                    <small>
                      {order.orderNumber} ·{' '}
                      {order.orderedAt ? dateLabel(order.orderedAt) : ''}
                    </small>
                    <small>
                      {order.orderStatus} ·{' '}
                      {order.paymentStatus === 'UNKNOWN'
                        ? '결제 확인 중'
                        : '결제 성공'}{' '}
                      · DROP #{order.dropId}
                    </small>
                  </div>
                  <b>{won(order.totalAmount ?? 0)}</b>
                  <div className="order-action">
                    {order.orderStatus === 'PAID' &&
                      (order.paymentStatus === 'UNKNOWN' ? (
                        <span
                          className="urgent-tag"
                          title="결제 취소 결과가 확인되기 전에는 배송 준비로 전환할 수 없습니다."
                        >
                          결제 확인 중
                        </span>
                      ) : (
                        <button
                          className="secondary-button"
                          type="button"
                          onClick={() => notify('곧 지원 예정이에요.')}
                        >
                          배송 준비
                        </button>
                      ))}
                    {order.orderStatus === 'PREPARING' && (
                      <button
                        className="secondary-button"
                        type="button"
                        onClick={() => notify('곧 지원 예정이에요.')}
                      >
                        송장 등록
                      </button>
                    )}
                  </div>
                  <button
                    className="order-toggle"
                    type="button"
                    aria-expanded={open}
                    aria-label={`${order.orderNumber} 주문 상세`}
                    onClick={() => toggleDetail(orderId)}
                  >
                    {open ? '▴' : '▾'}
                  </button>
                  {open && detailQuery.isLoading && (
                    <p className="seller-order-progress" role="status">
                      주문 상세를 불러오는 중이에요.
                    </p>
                  )}
                  {open && detailQuery.isError && (
                    <div
                      className="order-detail seller-order-error"
                      role="alert"
                    >
                      <p>{sellerOrderErrorMessage(detailQuery.error)}</p>
                      <button
                        type="button"
                        className="secondary-button"
                        onClick={() => detailQuery.refetch()}
                      >
                        다시 시도
                      </button>
                    </div>
                  )}
                  {open && detail && (
                    <SellerOrderDetail
                      detail={detail}
                      onEditShipment={
                        order.orderStatus === 'SHIPPED'
                          ? () => notify('곧 지원 예정이에요.')
                          : undefined
                      }
                    />
                  )}
                </article>
              )
            })}
          </div>
        ) : (
          <EmptyState
            title="조건에 맞는 주문이 없어요."
            link="/seller"
            label="대시보드로"
          />
        )}
        {result && listQuery.data && (listQuery.data.totalPages ?? 0) > 1 && (
          <nav
            className="seller-order-pagination"
            aria-label="주문 목록 페이지"
          >
            {result.page > 0 ? (
              <Link
                className="secondary-button"
                to={`/seller/orders?${changeSellerOrderParams(params, 'page', String(result.page - 1)).toString()}`}
              >
                이전
              </Link>
            ) : (
              <button type="button" className="secondary-button" disabled>
                이전
              </button>
            )}
            <span>
              {result.page + 1} / {listQuery.data.totalPages} 페이지
            </span>
            {listQuery.data.hasNext ? (
              <Link
                className="secondary-button"
                to={`/seller/orders?${changeSellerOrderParams(params, 'page', String(result.page + 1)).toString()}`}
              >
                다음
              </Link>
            ) : (
              <button type="button" className="secondary-button" disabled>
                다음
              </button>
            )}
          </nav>
        )}
      </section>
    </section>
  )
}
