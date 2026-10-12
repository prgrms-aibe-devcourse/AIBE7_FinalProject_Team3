import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import BuyerOrderAmounts from '../../features/order/components/BuyerOrderAmounts'
import BuyerOrderItem from '../../features/order/components/BuyerOrderItem'
import {
  canPay,
  withPaymentExample,
  type BuyerOrder,
  type PaymentDemoResult,
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

function CheckoutContent({
  order,
  updateBuyerOrder,
  notify,
}: Omit<Props, 'authenticated' | 'buyerOrders'> & { order: BuyerOrder }) {
  const [address, setAddress] = useState(order.shippingAddress)
  const [result, setResult] = useState<PaymentDemoResult>('SUCCEEDED')
  const payable = canPay(order)
  const checking =
    order.paymentStatus === 'PENDING' ||
    order.paymentStatus === 'UNKNOWN' ||
    order.reconciliationStatus === 'REQUIRED'

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!canPay(order)) return
    updateBuyerOrder(order.id, (current) =>
      withPaymentExample(current, result, address),
    )
    notify('Mock 결제 결과를 표시했어요. 실제 결제는 진행되지 않았습니다.')
  }

  const updateAddress = (
    field: keyof BuyerOrder['shippingAddress'],
    value: string,
  ) => setAddress((current) => ({ ...current, [field]: value }))

  return (
    <section className="page-section buyer-page">
      <p className="eyebrow">GRAB CHECKOUT</p>
      <h1>주문을 확인해 주세요</h1>
      <p className="buyer-intro">
        주문번호 {order.orderNumber} · 금액과 결제 마감은 주문 확정 응답의
        예시입니다.
      </p>
      <div className="buyer-layout">
        <div className="buyer-stack">
          <section className="panel buyer-panel">
            <h2>배송지</h2>
            <p className="buyer-note">
              배송지 입력은 화면 데모이며 서버로 전송하지 않습니다.
            </p>
            <form
              id="mock-payment-form"
              className="buyer-fields"
              onSubmit={submit}
            >
              <label>
                받는 분
                <input
                  required
                  value={address.recipient}
                  onChange={(event) =>
                    updateAddress('recipient', event.target.value)
                  }
                />
              </label>
              <label>
                연락처
                <input
                  required
                  inputMode="tel"
                  pattern="[0-9]{10,11}"
                  title="숫자 10~11자리"
                  value={address.phone}
                  onChange={(event) =>
                    updateAddress('phone', event.target.value)
                  }
                />
              </label>
              <label>
                우편번호
                <input
                  required
                  inputMode="numeric"
                  pattern="[0-9]{5}"
                  title="숫자 5자리"
                  value={address.postalCode}
                  onChange={(event) =>
                    updateAddress('postalCode', event.target.value)
                  }
                />
              </label>
              <label>
                기본 주소
                <input
                  required
                  value={address.address1}
                  onChange={(event) =>
                    updateAddress('address1', event.target.value)
                  }
                />
              </label>
              <label>
                상세 주소
                <input
                  value={address.address2}
                  onChange={(event) =>
                    updateAddress('address2', event.target.value)
                  }
                />
              </label>
            </form>
          </section>
          <section className="panel buyer-panel">
            <h2>주문 상품</h2>
            <BuyerOrderItem order={order} />
          </section>
        </div>
        <section className="panel buyer-panel buyer-summary">
          <h2>결제 금액</h2>
          <BuyerOrderAmounts order={order} />
          <p className="buyer-deadline">
            {order.paymentExpiresAt
              ? `결제 마감: ${dateLabel(order.paymentExpiresAt)}`
              : '결제 대기 시간이 종료됐어요.'}
          </p>
          {payable && (
            <label className="buyer-result-choice">
              결제 결과 예시
              <select
                value={result}
                onChange={(event) =>
                  setResult(event.target.value as PaymentDemoResult)
                }
              >
                <option value="SUCCEEDED">성공</option>
                <option value="FAILED">거절</option>
                <option value="PENDING">처리 중</option>
                <option value="UNKNOWN">결과 불명</option>
                <option value="REQUIRED">승인 후 주문 확인 필요</option>
              </select>
            </label>
          )}
          <button
            type="submit"
            form="mock-payment-form"
            className="primary-button full"
            disabled={!payable}
          >
            {payable ? 'Mock 결제 결과 보기' : '결제할 수 없는 주문'}
          </button>
          <small>
            토스페이먼츠 결제창은 열리지 않으며 실제 결제가 발생하지 않습니다.
          </small>
        </section>
      </div>
      <section className="panel buyer-panel buyer-result" aria-live="polite">
        <h2>결제 결과 상태</h2>
        {order.status === 'PAID' && order.reconciliationStatus === 'NONE' ? (
          <p>
            <strong>결제 성공 예시</strong> · 주문이 PAID인 Mock 상태입니다.
            실제 결제는 없었습니다.
          </p>
        ) : checking ? (
          <p>
            <strong>결과 확인 중</strong> · 결제창을 다시 열지 않고 주문 상태를
            확인해야 합니다.
          </p>
        ) : order.paymentStatus === 'FAILED' ? (
          <p>
            <strong>결제 거절 예시</strong> · 결제 마감 전이라면 다시 시도할 수
            있습니다.
          </p>
        ) : order.status === 'EXPIRED' || !payable ? (
          <p>
            <strong>결제 불가</strong> · 주문 상태 또는 결제 마감 시각을
            확인해주세요.
          </p>
        ) : (
          <p>
            <strong>결제 대기</strong> · 배송지와 서버 확정 금액을 확인해주세요.
          </p>
        )}
        <Link className="secondary-button" to={`/my/orders/${order.id}`}>
          주문 상세로 이동 ↗
        </Link>
      </section>
    </section>
  )
}

export default function CheckoutPage({
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
  return <CheckoutContent key={order.id} order={order} {...actions} />
}
