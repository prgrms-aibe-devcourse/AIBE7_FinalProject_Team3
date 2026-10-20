import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import type { AuthStatus } from '../../features/auth/logic/authUser'
import BuyerOrderAmounts from '../../features/order/components/BuyerOrderAmounts'
import BuyerOrderItem from '../../features/order/components/BuyerOrderItem'
import {
  buyerOrderStatusText,
  canCancel,
  canPay,
  cancelErrorMessage,
  isAuthError,
  isNotFoundError,
  isPaymentChecking,
  isRefunded,
  paymentStatusText,
  type BuyerOrderDetail,
  type BuyerOrderPayment,
} from '../../features/order/logic/buyerOrderView'
import {
  useCancelOrder,
  useMyOrder,
  useOrderPayments,
} from '../../features/order/logic/useBuyerOrders'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

type Props = {
  authStatus: AuthStatus
  notify: (message: string) => void
}

type CancelRequest = {
  idempotencyKey: string
  reason: string
  refundStatus: 'NONE' | 'SUCCEEDED' | 'UNKNOWN' | null
}

function PaymentHistory({
  payments,
  loading,
  failed,
  onRetry,
}: {
  payments: BuyerOrderPayment[]
  loading: boolean
  failed: boolean
  onRetry: () => void
}) {
  if (loading)
    return <small role="status">결제 이력을 불러오는 중이에요.</small>
  if (failed)
    return (
      <div className="buyer-payment-error" role="alert">
        <small>결제 이력을 불러오지 못했어요.</small>
        <button type="button" className="text-link" onClick={onRetry}>
          다시 시도
        </button>
      </div>
    )
  if (!payments.length) return <small>아직 결제 시도가 없어요.</small>
  return (
    <ol className="buyer-payment-history" aria-label="결제 이력">
      {payments.map((payment, index) => (
        <li key={payment.id}>
          <span>
            {index + 1}차 · {paymentStatusText[payment.status]}
            {payment.reconciliationRequired && ' · 확인 필요'}
          </span>
          <span>{won(payment.amount)}</span>
          <small>
            {dateLabel(payment.requestedAt)}
            {payment.failureMessage && ` · ${payment.failureMessage}`}
          </small>
        </li>
      ))}
    </ol>
  )
}

function OrderContent({
  order,
  payments,
  paymentsLoading,
  paymentsFailed,
  onRetryPayments,
  notify,
}: {
  order: BuyerOrderDetail
  payments: BuyerOrderPayment[]
  paymentsLoading: boolean
  paymentsFailed: boolean
  onRetryPayments: () => void
  notify: (message: string) => void
}) {
  const [reason, setReason] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState('')
  // 결과 불명(UNKNOWN)이면 같은 키·본문으로 다시 확인해야 하므로 마지막 요청을 화면 메모리에 둔다.
  const [lastCancel, setLastCancel] = useState<CancelRequest | null>(null)
  const cancelMutation = useCancelOrder()
  const paymentChecking = isPaymentChecking(order, payments)
  const cancelUnknown =
    lastCancel?.refundStatus === 'UNKNOWN' && order.status !== 'CANCELED'
  const cancelable = canCancel(order)
  // 결제 이력을 받기 전에는 보정 필요 여부를 알 수 없으므로 결제 이동을 막는다.
  const paymentsReady = !paymentsLoading && !paymentsFailed
  const payable = paymentsReady && canPay(order, payments)

  const sendCancel = (request: CancelRequest) => {
    cancelMutation.mutate(
      {
        orderId: order.id,
        idempotencyKey: request.idempotencyKey,
        body: { reason: request.reason },
      },
      {
        onSuccess: (result) => {
          const refundStatus = result.refundStatus ?? null
          setLastCancel({ ...request, refundStatus })
          setConfirming(false)
          setError('')
          if (refundStatus !== 'UNKNOWN') notify('주문을 취소했어요.')
        },
        onError: (cause) => {
          // 결과 확인 재요청이 실패해도 같은 키를 유지하고, 새 요청 실패면 다음엔 새 키를 쓴다.
          setLastCancel(request.refundStatus === 'UNKNOWN' ? request : null)
          setConfirming(false)
          setError(cancelErrorMessage(cause))
        },
      },
    )
  }

  const cancel = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!cancelable || cancelMutation.isPending) return
    if (!reason.trim()) {
      setError('취소 사유를 입력해주세요.')
      return
    }
    if (!confirming) {
      setConfirming(true)
      return
    }
    sendCancel({
      idempotencyKey: crypto.randomUUID(),
      reason: reason.trim(),
      refundStatus: null,
    })
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
          {order.items.map((item, index) => (
            <BuyerOrderItem
              key={index}
              productName={item.productName}
              optionLabel={item.optionName}
              quantity={item.quantity}
              amount={item.subtotal}
            />
          ))}
        </section>
        <section className="panel buyer-panel buyer-order-payment">
          <h2>결제</h2>
          <p>
            최근 결제 상태:{' '}
            {order.paymentStatus === null
              ? '시도 전'
              : paymentStatusText[order.paymentStatus]}
          </p>
          {paymentChecking && (
            <p className="buyer-alert">
              결제 결과를 확인 중입니다. 새 결제를 시작하지 마세요.
            </p>
          )}
          <BuyerOrderAmounts order={order} />
          {payable && (
            <Link className="primary-button" to={`/checkout/${order.id}`}>
              결제 화면으로 이동 ↗
            </Link>
          )}
          {order.status === 'PAYMENT_PENDING' &&
            paymentsReady &&
            !payable &&
            !paymentChecking && (
              <p className="buyer-note">
                결제 마감 또는 최근 결제 상태를 확인해주세요.
              </p>
            )}
          <h3>결제 이력</h3>
          <PaymentHistory
            payments={payments}
            loading={paymentsLoading}
            failed={paymentsFailed}
            onRetry={onRetryPayments}
          />
        </section>
        <section className="panel buyer-panel buyer-order-shipping">
          <h2>배송</h2>
          <p>
            상태:{' '}
            {order.status === 'DELIVERED'
              ? '배송 완료'
              : order.status === 'SHIPPED'
                ? '배송 중'
                : '송장 등록 전'}
          </p>
          {order.shippingAddress && (
            <p className="buyer-note">
              받는 분 {order.shippingAddress.recipient} ·{' '}
              {order.shippingAddress.address1} {order.shippingAddress.address2}
              {order.shippingAddress.deliveryMemo &&
                ` · ${order.shippingAddress.deliveryMemo}`}
            </p>
          )}
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
          {cancelUnknown && lastCancel ? (
            <div className="buyer-cancel-form">
              <p className="buyer-alert">
                취소 결과 확인 중입니다. 완료로 표시하거나 새 취소 요청을
                시작하지 않습니다.
              </p>
              {error && (
                <p className="form-error" role="alert">
                  {error}
                </p>
              )}
              <button
                type="button"
                className="secondary-button"
                disabled={cancelMutation.isPending}
                onClick={() => sendCancel(lastCancel)}
              >
                {cancelMutation.isPending ? '확인 중…' : '취소 결과 확인'}
              </button>
            </div>
          ) : order.status === 'CANCELED' ? (
            <p>
              환불 상태:{' '}
              {lastCancel?.refundStatus === 'SUCCEEDED' || isRefunded(payments)
                ? '환불 완료'
                : '결제 전 취소'}
            </p>
          ) : order.paymentStatus === 'PENDING' &&
            order.status === 'PAYMENT_PENDING' ? (
            <p className="buyer-note">
              결제 승인을 기다리는 중이라 지금은 취소할 수 없습니다.
            </p>
          ) : cancelable ? (
            <form className="buyer-cancel-form" onSubmit={cancel}>
              <label>
                취소 사유 (필수)
                <textarea
                  required
                  maxLength={500}
                  value={reason}
                  disabled={cancelMutation.isPending}
                  onChange={(event) => {
                    setReason(event.target.value)
                    setConfirming(false)
                    setError('')
                  }}
                />
              </label>
              {confirming && (
                <p className="buyer-alert">
                  “{reason.trim()}” 사유로 주문을 취소할까요? 결제한 주문은
                  결제도 함께 취소됩니다.
                </p>
              )}
              {error && (
                <p className="form-error" role="alert">
                  {error}
                </p>
              )}
              <button
                type="submit"
                className="secondary-button"
                disabled={cancelMutation.isPending}
              >
                {cancelMutation.isPending
                  ? '취소 요청 중…'
                  : confirming
                    ? '주문 취소'
                    : '취소 내용 확인'}
              </button>
            </form>
          ) : (
            <p className="buyer-note">
              배송이 시작됐거나 주문이 종료돼 취소할 수 없습니다.
            </p>
          )}
        </section>
      </div>
      <Link className="text-link buyer-back" to="/my">
        ← 내 주문 목록
      </Link>
    </section>
  )
}

export default function OrderDetailPage({ authStatus, notify }: Props) {
  const { orderId = '' } = useParams()
  const location = useLocation()
  const authenticated = authStatus === 'authenticated'
  const orderQuery = useMyOrder(orderId, authenticated)
  const paymentsQuery = useOrderPayments(orderId, authenticated)

  if (authStatus === 'loading') return <AuthLoading />
  if (authStatus === 'anonymous')
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  if (orderQuery.isPending)
    return (
      <div role="status" aria-live="polite">
        <EmptyState title="주문을 불러오는 중이에요." variant="page" />
      </div>
    )
  if (orderQuery.isError) {
    // 다른 사용자의 주문과 없는 주문은 서버가 같은 404로 응답한다.
    if (isNotFoundError(orderQuery.error))
      return (
        <EmptyState
          title="주문을 찾을 수 없어요."
          link="/my"
          label="내 주문으로 돌아가기"
          variant="page"
        />
      )
    return (
      <div className="catalog-error" role="alert">
        <EmptyState
          title={
            isAuthError(orderQuery.error)
              ? '주문을 볼 권한이 없어요.'
              : '주문을 불러오지 못했어요.'
          }
          description="잠시 후 다시 시도해주세요."
          variant="page"
        />
        <button
          type="button"
          className="secondary-button"
          onClick={() => void orderQuery.refetch()}
        >
          다시 시도
        </button>
      </div>
    )
  }
  return (
    <OrderContent
      key={orderQuery.data.id}
      order={orderQuery.data}
      payments={paymentsQuery.data ?? []}
      paymentsLoading={paymentsQuery.isPending}
      paymentsFailed={paymentsQuery.isError}
      onRetryPayments={() => void paymentsQuery.refetch()}
      notify={notify}
    />
  )
}
