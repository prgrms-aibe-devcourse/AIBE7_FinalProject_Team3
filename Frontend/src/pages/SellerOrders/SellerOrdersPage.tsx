import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import type { SellerOrderStatus } from '../../features/order/mock/mockSellerOrders'
import type { ShipmentRequest } from '../../api/seller/orders'
import {
  changeSellerOrderParams,
  readSellerOrderQuery,
} from '../../features/order/logic/sellerOrderQuery'
import {
  usePrepareShipment,
  useRegisterShipment,
  useSellerOrder,
  useSellerOrders,
  useUpdateShipment,
} from '../../features/order/logic/useSellerOrders'
import {
  sellerOrderErrorMessage,
  shipmentErrorMessage,
} from '../../features/order/logic/sellerOrderState'
import SellerOrderDetail from '../../features/order/components/SellerOrderDetail'
import ShipmentForm from '../../features/order/components/ShipmentForm'
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
  const [editing, setEditing] = useState(false)
  const [formKey, setFormKey] = useState(0)
  const [actionError, setActionError] = useState('')
  const [busyId, setBusyId] = useState<string | null>(null)
  // 같은 본문으로 재시도할 때만 Idempotency-Key를 재사용한다(ORDER.md 2.4).
  // 내용을 바꿔 다시 보내면 새 키를 발급해 409 DUPLICATE_IDEMPOTENCY_KEY를 피한다.
  const [lastAttempt, setLastAttempt] = useState<{
    key: string
    body: ShipmentRequest
  } | null>(null)

  const prepareShipmentMutation = usePrepareShipment()
  const registerShipmentMutation = useRegisterShipment()
  const updateShipmentMutation = useUpdateShipment()

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
    setEditing(false)
    setActionError('')
  }

  const openShipmentForm = (orderId: string) => {
    setOpenId(orderId)
    setEditing(true)
    setFormKey((current) => current + 1)
    setLastAttempt(null)
    setActionError('')
  }

  const prepare = (order: { orderId?: string; orderNumber?: string }) => {
    const orderId = order.orderId ?? ''
    setActionError('')
    setBusyId(orderId)
    prepareShipmentMutation.mutate(orderId, {
      onSuccess: () => {
        setBusyId(null)
        notify(`${order.orderNumber} 배송 준비로 전환했어요.`)
      },
      onError: (error) => {
        setBusyId(null)
        setActionError(shipmentErrorMessage(error))
      },
    })
  }

  const saveShipment = (
    order: { orderId?: string; orderNumber?: string; orderStatus?: string },
    body: ShipmentRequest,
  ) => {
    const orderId = order.orderId ?? ''
    const isUpdate = order.orderStatus === 'SHIPPED'
    setActionError('')
    setBusyId(orderId)
    const onSettledSuccess = () => {
      setBusyId(null)
      setEditing(false)
      notify(
        isUpdate
          ? `${order.orderNumber} 송장을 수정했어요.`
          : `${order.orderNumber} 송장을 등록하고 발송 처리했어요.`,
      )
    }
    const onSettledError = (error: unknown) => {
      setBusyId(null)
      setActionError(shipmentErrorMessage(error))
    }
    if (isUpdate) {
      updateShipmentMutation.mutate(
        { orderId, body },
        { onSuccess: onSettledSuccess, onError: onSettledError },
      )
      return
    }
    const sameAsLastAttempt =
      lastAttempt?.body.carrier === body.carrier &&
      lastAttempt?.body.trackingNumber === body.trackingNumber
    const idempotencyKey = sameAsLastAttempt
      ? lastAttempt.key
      : crypto.randomUUID()
    setLastAttempt({ key: idempotencyKey, body })
    registerShipmentMutation.mutate(
      { orderId, idempotencyKey, body },
      { onSuccess: onSettledSuccess, onError: onSettledError },
    )
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
          deliveryStage: detailQuery.data.shipping.deliveryStage ?? null,
        }
      : null,
  }

  return (
    <section className="page-section seller-page seller-orders-page">
      <div className="seller-title">
        <div>
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
                      (order.paymentStatus === 'UNKNOWN' ||
                      order.cancellationInProgress ? (
                        <span
                          className="urgent-tag"
                          title="결제 취소 결과가 확인되기 전에는 배송 준비로 전환할 수 없습니다."
                        >
                          {order.cancellationInProgress
                            ? '취소 확인 중'
                            : '결제 확인 중'}
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
                    {order.orderStatus === 'PREPARING' &&
                      (order.paymentStatus === 'UNKNOWN' ||
                      order.cancellationInProgress ? (
                        <span
                          className="urgent-tag"
                          title="결제 취소 결과가 확인되기 전에는 발송할 수 없습니다."
                        >
                          {order.cancellationInProgress
                            ? '취소 확인 중'
                            : '결제 확인 중'}
                        </span>
                      ) : (
                        <button
                          className="secondary-button"
                          type="button"
                          disabled={busyId !== null}
                          onClick={() => openShipmentForm(orderId)}
                        >
                          송장 등록
                        </button>
                      ))}
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
                          ? () => openShipmentForm(orderId)
                          : undefined
                      }
                    />
                  )}
                  {open && actionError && (
                    <div className="seller-order-error" role="alert">
                      <p>{actionError}</p>
                      <button
                        type="button"
                        className="text-link"
                        onClick={() => setActionError('')}
                      >
                        닫기
                      </button>
                    </div>
                  )}
                  {open && busyId === orderId && (
                    <p className="seller-order-progress" role="status">
                      배송 작업을 처리하는 중이에요.
                    </p>
                  )}
                  {open && editing && detail && (
                    <ShipmentForm
                      key={formKey}
                      initial={{
                        carrier: detail.shipping?.carrier ?? '',
                        trackingNumber: detail.shipping?.trackingNumber ?? '',
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
