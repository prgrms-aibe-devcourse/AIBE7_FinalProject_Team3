import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import BuyerOrderAmounts from '../../features/order/components/BuyerOrderAmounts'
import BuyerOrderItem from '../../features/order/components/BuyerOrderItem'
import {
  buyerOrderStatusText,
  canCancel,
  canPay,
  withCancelExample,
  type BuyerOrder,
  type CancelDemoResult,
} from '../../features/order/mock/mockBuyerOrders'
import { dateLabel } from '../../utils/date'

type Props = {
  authenticated: boolean
  buyerOrders: BuyerOrder[]
  updateBuyerOrder: (
    id: string,
    update: (order: BuyerOrder) => BuyerOrder,
  ) => void
  notify: (message: string) => void
}

function OrderContent({
  order,
  updateBuyerOrder,
  notify,
}: Omit<Props, 'authenticated' | 'buyerOrders'> & { order: BuyerOrder }) {
  const [reason, setReason] = useState('')
  const [result, setResult] = useState<CancelDemoResult>('SUCCEEDED')
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState('')
  const cancelable = canCancel(order)
  const paymentChecking =
    order.paymentStatus === 'PENDING' ||
    order.paymentStatus === 'UNKNOWN' ||
    order.reconciliationStatus === 'REQUIRED'

  const cancel = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!cancelable) return
    if (!reason.trim()) {
      setError('취소 사유를 입력해주세요.')
      return
    }
    if (!confirming) {
      setConfirming(true)
      return
    }
    if (result === 'FAILED' || result === 'CONFLICT') {
      setError(
        result === 'FAILED'
          ? '결제 취소 실패 예시입니다. 주문 상태는 유지됩니다.'
          : '주문 상태가 변경돼 취소할 수 없는 예시입니다.',
      )
      setConfirming(false)
      return
    }
    updateBuyerOrder(order.id, (current) =>
      withCancelExample(current, result, reason),
    )
    notify('Mock 취소 결과를 표시했어요. 실제 취소·환불은 진행되지 않았습니다.')
    setConfirming(false)
    setError('')
  }

  return (
    <section className="page-section buyer-page">
      <p className="eyebrow">MY ORDER</p>
      <h1>주문 {order.orderNumber}</h1>
      <p className="buyer-intro">
        <span className="buyer-status">
          {buyerOrderStatusText[order.status]}
        </span>{' '}
        · 주문일 {dateLabel(order.orderedAt)}
      </p>
      <div className="buyer-order-layout">
        <section className="panel buyer-panel buyer-order-product">
          <h2>주문 상품</h2>
          <BuyerOrderItem order={order} />
        </section>
        <section className="panel buyer-panel buyer-order-payment">
          <h2>결제</h2>
          <p>
            최근 결제 상태:{' '}
            {order.paymentStatus === null
              ? '시도 전'
              : order.paymentStatus === 'SUCCEEDED'
                ? '성공'
                : order.paymentStatus === 'FAILED'
                  ? '실패'
                  : '결과 확인 중'}
          </p>
          {paymentChecking && (
            <p className="buyer-alert">
              결제 결과를 확인 중입니다. 새 결제를 시작하지 마세요.
            </p>
          )}
          <BuyerOrderAmounts order={order} />
          {canPay(order) && (
            <Link className="primary-button" to={`/checkout/${order.id}`}>
              결제 화면으로 이동 ↗
            </Link>
          )}
          {order.status === 'PAYMENT_PENDING' &&
            !canPay(order) &&
            !paymentChecking && (
              <p className="buyer-note">
                결제 마감 또는 최근 결제 상태를 확인해주세요.
              </p>
            )}
          <small>전체 결제 이력은 조회 API 연동 후 표시합니다.</small>
        </section>
        <section className="panel buyer-panel buyer-order-shipping">
          <h2>배송</h2>
          <p>
            상태:{' '}
            {order.shipping.status === 'DELIVERED'
              ? '배송 완료'
              : order.shipping.status === 'SHIPPED'
                ? '배송 중'
                : '송장 등록 전'}
          </p>
          <p className="buyer-note">
            받는 분 {order.shippingAddress.recipient || '미입력'} ·{' '}
            {order.shippingAddress.address1 || '주소 미입력'}{' '}
            {order.shippingAddress.address2}
          </p>
          {order.shipping.carrier && order.shipping.trackingNumber ? (
            <dl className="buyer-shipping-info">
              <div>
                <dt>택배사</dt>
                <dd>{order.shipping.carrier}</dd>
              </div>
              <div>
                <dt>운송장번호</dt>
                <dd>{order.shipping.trackingNumber}</dd>
              </div>
              {order.shipping.deliveredAt && (
                <div>
                  <dt>배송 완료</dt>
                  <dd>{dateLabel(order.shipping.deliveredAt)}</dd>
                </div>
              )}
            </dl>
          ) : (
            <p className="buyer-note">
              송장이 등록되면 택배사와 운송장번호가 표시됩니다.
            </p>
          )}
        </section>
        <section className="panel buyer-panel buyer-order-cancel">
          <h2>주문 취소</h2>
          {order.refundStatus === 'UNKNOWN' ? (
            <p className="buyer-alert">
              취소 결과 확인 중입니다. 완료로 표시하거나 새 취소 요청을 시작하지
              않습니다.
            </p>
          ) : order.status === 'CANCELED' ? (
            <p>
              취소 사유: {order.cancelReason} · 환불 상태:{' '}
              {order.refundStatus === 'SUCCEEDED'
                ? '완료 예시'
                : '결제 전 취소'}
            </p>
          ) : paymentChecking ? (
            <p className="buyer-note">
              결제 결과를 확인 중이라 취소할 수 없습니다.
            </p>
          ) : cancelable ? (
            <form className="buyer-cancel-form" onSubmit={cancel}>
              <label>
                취소 사유 (필수)
                <textarea
                  required
                  maxLength={500}
                  value={reason}
                  onChange={(event) => {
                    setReason(event.target.value)
                    setConfirming(false)
                    setError('')
                  }}
                />
              </label>
              <label className="buyer-result-choice">
                취소 결과 예시
                <select
                  value={result}
                  onChange={(event) => {
                    setResult(event.target.value as CancelDemoResult)
                    setConfirming(false)
                    setError('')
                  }}
                >
                  <option value="SUCCEEDED">취소 완료</option>
                  <option value="FAILED">결제 취소 실패</option>
                  <option value="UNKNOWN">결과 확인 중</option>
                  <option value="CONFLICT">주문 상태 충돌</option>
                </select>
              </label>
              {confirming && (
                <p className="buyer-alert">
                  “{reason.trim()}” 사유로 취소하는 Mock 결과를 표시할까요?
                </p>
              )}
              {error && (
                <p className="form-error" role="alert">
                  {error}
                </p>
              )}
              <button type="submit" className="secondary-button">
                {confirming ? 'Mock 취소 결과 보기' : '취소 내용 확인'}
              </button>
            </form>
          ) : (
            <p className="buyer-note">
              배송이 시작됐거나 주문이 종료돼 취소할 수 없습니다.
            </p>
          )}
          <small>실제 주문 취소나 환불은 진행되지 않습니다.</small>
        </section>
      </div>
      <Link className="text-link buyer-back" to="/my">
        ← 내 주문 목록
      </Link>
    </section>
  )
}

export default function OrderDetailPage({
  authenticated,
  buyerOrders,
  ...actions
}: Props) {
  const { orderId } = useParams()
  const location = useLocation()
  if (!authenticated)
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  const order = buyerOrders.find((item) => item.id === orderId)
  if (!order)
    return (
      <EmptyState
        title="주문을 찾을 수 없어요."
        link="/my"
        label="내 주문으로 돌아가기"
        variant="page"
      />
    )
  return <OrderContent key={order.id} order={order} {...actions} />
}
