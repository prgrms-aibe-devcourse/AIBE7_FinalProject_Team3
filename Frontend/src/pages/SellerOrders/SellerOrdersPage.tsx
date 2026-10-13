import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import type {
  SellerOrder,
  SellerOrderStatus,
} from '../../features/order/mock/mockSellerOrders'
import {
  orderDetails,
  sellerOrders,
} from '../../features/order/mock/mockSellerOrders'
import {
  changeSellerOrderParams,
  querySellerOrders,
} from '../../features/order/logic/sellerOrderQuery'
import SellerOrderDetail from '../../features/order/components/SellerOrderDetail'
import ShipmentForm from '../../features/order/components/ShipmentForm'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

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
  const [orders, setOrders] = useState(sellerOrders)
  const [details, setDetails] = useState(orderDetails)
  const [openId, setOpenId] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [listResult, setListResult] = useState<
    'READY' | 'LOADING' | 'ERROR' | 'FORBIDDEN'
  >('READY')
  const [detailResult, setDetailResult] = useState<'READY' | 'ERROR'>('READY')
  const [actionResult, setActionResult] = useState<
    'SUCCESS' | 'FAILURE' | 'CONFLICT'
  >('SUCCESS')
  const [error, setError] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  // 송장 버튼을 다시 누르면 입력을 저장된 값으로 되돌린다. key를 바꿔 폼을 새로 그린다.
  const [formKey, setFormKey] = useState(0)

  let result: ReturnType<typeof querySellerOrders> | null = null
  try {
    result = querySellerOrders(orders, params)
  } catch (error) {
    if (!(error instanceof RangeError)) throw error
  }
  const dropId = params.get('dropId')
  const paymentStatus = params.get('paymentStatus') ?? ''
  const selectedStatus = result?.orderStatus ?? 'PAID'
  const matching = orders.filter(
    (order) => !dropId || String(order.dropId) === dropId,
  )
  const countOf = (status: SellerOrderStatus) =>
    matching.filter(
      (order) =>
        order.orderStatus === status &&
        (!paymentStatus || order.paymentStatus === paymentStatus),
    ).length
  const setFilter = (
    key: 'orderStatus' | 'paymentStatus' | 'dropId',
    value: string,
  ) => {
    setParams(changeSellerOrderParams(params, key, value))
    close()
    setError('')
  }

  const close = () => {
    setOpenId(null)
    setEditing(false)
  }

  const toggleDetail = (orderId: string) => {
    if (openId === orderId) return close()
    setOpenId(orderId)
    setEditing(false)
    setError('')
  }

  const setStatus = (orderId: string, orderStatus: SellerOrderStatus) =>
    setOrders((current) =>
      current.map((item) =>
        item.orderId === orderId ? { ...item, orderStatus } : item,
      ),
    )

  const finishAction = async (order: SellerOrder, done: () => void) => {
    setBusyId(order.orderId)
    setError('')
    await new Promise((resolve) => window.setTimeout(resolve, 300))
    setBusyId(null)
    if (actionResult !== 'SUCCESS') {
      setError(
        actionResult === 'CONFLICT'
          ? '주문 상태가 바뀌었어요. 목록을 다시 확인해주세요.'
          : '배송 작업을 처리하지 못했어요. 다시 시도해주세요.',
      )
      setOpenId(order.orderId)
      return
    }
    done()
  }

  const prepare = (order: SellerOrder) => {
    void finishAction(order, () => {
      setStatus(order.orderId, 'PREPARING')
      close()
      notify(`${order.orderNumber} 배송 준비로 전환했어요.`)
    })
  }

  const openShipmentForm = (order: SellerOrder) => {
    setOpenId(order.orderId)
    setEditing(true)
    setFormKey((current) => current + 1)
  }

  const saveShipment = (
    order: SellerOrder,
    { carrier, trackingNumber }: { carrier: string; trackingNumber: string },
  ) => {
    void finishAction(order, () => {
      const isUpdate = order.orderStatus === 'SHIPPED'
      setDetails((current) => ({
        ...current,
        [order.orderId]: {
          ...current[order.orderId],
          shipping: {
            carrier,
            trackingNumber,
            deliveredAt: current[order.orderId]?.shipping?.deliveredAt ?? null,
          },
        },
      }))
      if (!isUpdate) setStatus(order.orderId, 'SHIPPED')
      if (isUpdate) setEditing(false)
      else close()
      notify(
        isUpdate
          ? `${order.orderNumber} 송장을 수정했어요.`
          : `${order.orderNumber} 송장을 등록하고 발송 처리했어요.`,
      )
    })
  }

  return (
    <section className="page-section seller-page seller-orders-page">
      <div className="seller-title">
        <div>
          <p className="eyebrow">ORDER DESK</p>
          <h1>주문·배송</h1>
          <p>
            본인 DROP의 주문과 발송 작업을 확인합니다. 화면의 배송 처리는
            예시입니다.
          </p>
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
              {item.label} {countOf(item.key)}
            </button>
          ))}
        </div>
        <div className="seller-order-selects">
          <label>
            DROP 필터
            <select
              value={dropId ?? ''}
              onChange={(event) => setFilter('dropId', event.target.value)}
            >
              <option value="">전체 DROP</option>
              {[...new Set(orders.map((order) => order.dropId))].map((id) => (
                <option value={id} key={id}>
                  DROP #{id}
                </option>
              ))}
            </select>
          </label>
          <label>
            결제 상태 필터
            <select
              value={paymentStatus}
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
        <div className="seller-order-examples">
          <label>
            목록 결과 예시
            <select
              value={listResult}
              onChange={(event) =>
                setListResult(event.target.value as typeof listResult)
              }
            >
              <option value="READY">정상</option>
              <option value="LOADING">조회 중</option>
              <option value="ERROR">조회 실패</option>
              <option value="FORBIDDEN">권한 오류</option>
            </select>
          </label>
          <label>
            상세 결과 예시
            <select
              value={detailResult}
              onChange={(event) =>
                setDetailResult(event.target.value as typeof detailResult)
              }
            >
              <option value="READY">정상</option>
              <option value="ERROR">조회 실패</option>
            </select>
          </label>
          <label>
            배송 결과 예시
            <select
              value={actionResult}
              onChange={(event) =>
                setActionResult(event.target.value as typeof actionResult)
              }
            >
              <option value="SUCCESS">성공</option>
              <option value="FAILURE">처리 실패</option>
              <option value="CONFLICT">상태 충돌</option>
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
        ) : listResult === 'LOADING' ? (
          <div role="status">
            <EmptyState title="주문 목록을 불러오는 중이에요." />
          </div>
        ) : listResult === 'FORBIDDEN' ? (
          <div role="alert">
            <EmptyState title="판매자 주문을 볼 권한이 없어요." />
          </div>
        ) : listResult === 'ERROR' ? (
          <div role="alert" className="seller-order-error">
            <EmptyState title="주문 목록을 불러오지 못했어요." />
            <button
              type="button"
              className="secondary-button"
              onClick={() => setListResult('READY')}
            >
              다시 시도
            </button>
          </div>
        ) : result.content.length ? (
          <div className="order-list">
            {result.content.map((order) => {
              const open = openId === order.orderId
              const detail = details[order.orderId]
              return (
                <article className="order-row" key={order.orderId}>
                  <div className="order-main">
                    <strong>{order.productName}</strong>
                    <small>
                      {order.orderNumber} · {dateLabel(order.orderedAt)}
                    </small>
                    <small>
                      {order.orderStatus} ·{' '}
                      {order.paymentStatus === 'UNKNOWN'
                        ? '결제 확인 중'
                        : '결제 성공'}{' '}
                      · DROP #{order.dropId}
                    </small>
                  </div>
                  <b>{won(order.totalAmount)}</b>
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
                          disabled={busyId !== null}
                          onClick={() => prepare(order)}
                        >
                          배송 준비
                        </button>
                      ))}
                    {order.orderStatus === 'PREPARING' && (
                      <button
                        className="secondary-button"
                        type="button"
                        disabled={busyId !== null}
                        onClick={() => openShipmentForm(order)}
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
                    onClick={() => toggleDetail(order.orderId)}
                  >
                    {open ? '▴' : '▾'}
                  </button>
                  {open && detailResult === 'ERROR' && (
                    <div
                      className="order-detail seller-order-error"
                      role="alert"
                    >
                      <p>주문 상세를 불러오지 못했어요.</p>
                      <button
                        type="button"
                        className="secondary-button"
                        onClick={() => setDetailResult('READY')}
                      >
                        다시 시도
                      </button>
                    </div>
                  )}
                  {open && detail && detailResult === 'READY' && (
                    <SellerOrderDetail
                      detail={detail}
                      onEditShipment={
                        order.orderStatus === 'SHIPPED'
                          ? () => openShipmentForm(order)
                          : undefined
                      }
                    />
                  )}
                  {open && error && (
                    <div className="seller-order-error" role="alert">
                      <p>{error}</p>
                      <button
                        type="button"
                        className="text-link"
                        onClick={() => setError('')}
                      >
                        목록 다시 확인
                      </button>
                    </div>
                  )}
                  {open && busyId === order.orderId && (
                    <p className="seller-order-progress" role="status">
                      배송 작업을 처리하는 중이에요.
                    </p>
                  )}
                  {open && editing && detailResult === 'READY' && (
                    <ShipmentForm
                      key={formKey}
                      initial={{
                        carrier: detail?.shipping?.carrier ?? '',
                        trackingNumber: detail?.shipping?.trackingNumber ?? '',
                      }}
                      submitLabel={
                        order.orderStatus === 'SHIPPED'
                          ? '수정 저장'
                          : '발송 처리'
                      }
                      disabled={busyId !== null}
                      onSubmit={(shipment) => saveShipment(order, shipment)}
                      onCancel={() => setEditing(false)}
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
        {result && result.totalPages > 1 && listResult === 'READY' && (
          <nav
            className="seller-order-pagination"
            aria-label="주문 목록 페이지"
          >
            {result.page > 0 ? (
              <Link
                className="secondary-button"
                to={`/seller/orders?${changeSellerOrderParams(params, 'page', String(result.page - 1))}`}
              >
                이전
              </Link>
            ) : (
              <button type="button" className="secondary-button" disabled>
                이전
              </button>
            )}
            <span>
              {result.page + 1} / {result.totalPages} 페이지
            </span>
            {result.page + 1 < result.totalPages ? (
              <Link
                className="secondary-button"
                to={`/seller/orders?${changeSellerOrderParams(params, 'page', String(result.page + 1))}`}
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
