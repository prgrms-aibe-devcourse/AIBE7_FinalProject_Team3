import { useState } from 'react'
import EmptyState from '../../components/EmptyState/EmptyState'
import type {
  SellerOrder,
  SellerOrderStatus,
} from '../../features/order/mockSellerOrders'
import {
  orderDetails,
  sellerOrders,
} from '../../features/order/mockSellerOrders'
import SellerOrderDetail from '../../features/order/SellerOrderDetail'
import ShipmentForm from '../../features/order/ShipmentForm'
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
  const [orders, setOrders] = useState(sellerOrders)
  const [details, setDetails] = useState(orderDetails)
  const [tab, setTab] = useState<SellerOrderStatus>('PAID')
  const [openId, setOpenId] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  // 송장 버튼을 다시 누르면 입력을 저장된 값으로 되돌린다. key를 바꿔 폼을 새로 그린다.
  const [formKey, setFormKey] = useState(0)

  const countOf = (status: SellerOrderStatus) =>
    orders.filter((order) => order.orderStatus === status).length
  const visible = orders.filter((order) => order.orderStatus === tab)

  const close = () => {
    setOpenId(null)
    setEditing(false)
  }

  // GET /seller/orders/{orderId} (GR-39) — 펼칠 때 상세를 받아온다.
  const toggleDetail = (orderId: string) => {
    if (openId === orderId) return close()
    setOpenId(orderId)
    setEditing(false)
  }

  const setStatus = (orderId: string, orderStatus: SellerOrderStatus) =>
    setOrders((current) =>
      current.map((item) =>
        item.orderId === orderId ? { ...item, orderStatus } : item,
      ),
    )

  // POST /seller/orders/{orderId}/prepare-shipment (GR-40)
  const prepare = (order: SellerOrder) => {
    setStatus(order.orderId, 'PREPARING')
    notify(`${order.orderNumber} 배송 준비로 전환했어요.`)
  }

  const openShipmentForm = (order: SellerOrder) => {
    setOpenId(order.orderId)
    setEditing(true)
    setFormKey((current) => current + 1)
  }

  // 등록: POST /seller/orders/{orderId}/shipment + Idempotency-Key (GR-42)
  // 수정: PATCH /seller/orders/{orderId}/shipment — 상태 전이 없이 송장만 갱신 (명세 추가 필요)
  // 수정은 SHIPPED까지만 허용한다. 오타 교정이 목적이고,
  // DELIVERED 이후에 번호를 바꾸면 끝난 배송 이력과 어긋난다.
  const saveShipment = (
    order: SellerOrder,
    { carrier, trackingNumber }: { carrier: string; trackingNumber: string },
  ) => {
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
    setEditing(false)
    notify(
      isUpdate
        ? `${order.orderNumber} 송장을 수정했어요.`
        : `${order.orderNumber} 송장을 등록하고 발송 처리했어요.`,
    )
  }

  return (
    <section className="page-section seller-page">
      <div className="seller-title">
        <div>
          <p className="eyebrow">ORDER DESK</p>
          <h1>주문 관리</h1>
          <p>결제 완료 주문을 배송 준비로 바꾸고 송장을 등록합니다.</p>
        </div>
      </div>
      <div className="chips order-tabs">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            className={tab === item.key ? 'active' : undefined}
            onClick={() => {
              setTab(item.key)
              close()
            }}
          >
            {item.label} {countOf(item.key)}
          </button>
        ))}
      </div>
      <section className="panel">
        {visible.length ? (
          <div className="order-list">
            {visible.map((order) => {
              const open = openId === order.orderId
              const detail = details[order.orderId]
              return (
                <article className="order-row" key={order.orderId}>
                  <div className="order-main">
                    <strong>{order.productName}</strong>
                    <small>
                      {order.orderNumber} · {dateLabel(order.orderedAt)}
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
                          onClick={() => prepare(order)}
                        >
                          배송 준비
                        </button>
                      ))}
                    {order.orderStatus === 'PREPARING' && (
                      <button
                        className="secondary-button"
                        type="button"
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
                  {open && detail && (
                    <SellerOrderDetail
                      detail={detail}
                      onEditShipment={
                        order.orderStatus === 'SHIPPED'
                          ? () => openShipmentForm(order)
                          : undefined
                      }
                    />
                  )}
                  {open && editing && (
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
            title="해당 상태의 주문이 없어요."
            link="/seller"
            label="대시보드로"
          />
        )}
      </section>
    </section>
  )
}
