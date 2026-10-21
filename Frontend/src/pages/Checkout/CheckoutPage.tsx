import {
  useEffect,
  useRef,
  useState,
  type ComponentProps,
  type FormEvent,
} from 'react'
import {
  Link,
  Navigate,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom'
import type { PaymentRequest } from '../../api/payment/payments'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import type { AuthStatus } from '../../features/auth/logic/authUser'
import { toDropDetail } from '../../features/drop/logic/dropDetailView'
import { useDropDetail } from '../../features/drop/logic/useDrops'
import BuyerOrderAmounts from '../../features/order/components/BuyerOrderAmounts'
import BuyerOrderItem from '../../features/order/components/BuyerOrderItem'
import {
  buyerOrderStatusText,
  canPay,
  isAuthError,
  isNotFoundError,
  isPaymentChecking,
  needsReconciliation,
} from '../../features/order/logic/buyerOrderView'
import {
  createOrderErrorMessage,
  isCallbackMismatch,
  paymentErrorMessage,
  readCheckoutDraft,
  toDraftSummary,
  toOrderCreateRequest,
  toPaymentOutcome,
  type CheckoutDraft,
  type PaymentOutcome,
  type ShippingAddressInput,
} from '../../features/order/logic/checkoutView'
import {
  TossConfigError,
  clearTossCallback,
  isTossUserCancel,
  openTossPayment,
  readTossCallback,
} from '../../features/order/logic/tossPayment'
import {
  useApprovePayment,
  useCreateOrder,
  useMyOrder,
  useOrderPayments,
} from '../../features/order/logic/useBuyerOrders'
import { dateLabel } from '../../utils/date'

type Props = {
  authStatus: AuthStatus
  notify: (message: string) => void
}

const EMPTY_ADDRESS: ShippingAddressInput = {
  recipient: '',
  phone: '',
  postalCode: '',
  address1: '',
  address2: '',
  deliveryMemo: '',
}

function AddressFields({
  address,
  disabled,
  onChange,
}: {
  address: ShippingAddressInput
  disabled: boolean
  onChange: (field: keyof ShippingAddressInput, value: string) => void
}) {
  const field = (
    name: keyof ShippingAddressInput,
    label: string,
    props: ComponentProps<'input'> = {},
  ) => (
    <label>
      {label}
      <input
        value={address[name]}
        disabled={disabled}
        onChange={(event) => onChange(name, event.target.value)}
        {...props}
      />
    </label>
  )
  return (
    <>
      {field('recipient', '받는 분', { required: true, maxLength: 100 })}
      {field('phone', '연락처', {
        required: true,
        inputMode: 'tel',
        pattern: '[0-9]{10,11}',
        title: '숫자 10~11자리',
      })}
      {field('postalCode', '우편번호', {
        required: true,
        inputMode: 'numeric',
        pattern: '[0-9]{5}',
        title: '숫자 5자리',
      })}
      {field('address1', '기본 주소', { required: true, maxLength: 300 })}
      {field('address2', '상세 주소', { maxLength: 300 })}
      {field('deliveryMemo', '배송 요청사항', { maxLength: 300 })}
    </>
  )
}

function DraftCheckout({
  draft,
  notify,
}: {
  draft: CheckoutDraft
  notify: (message: string) => void
}) {
  const navigate = useNavigate()
  const detailQuery = useDropDetail(draft.dropId)
  const createOrder = useCreateOrder()
  const [address, setAddress] = useState(EMPTY_ADDRESS)
  const [error, setError] = useState('')
  // 같은 입력의 재시도는 같은 키, 입력을 바꾸면 새 키를 쓴다(CheckoutPage.md).
  const [lastRequest, setLastRequest] = useState<{
    key: string
    body: string
  } | null>(null)

  if (detailQuery.isPending)
    return (
      <div role="status" aria-live="polite">
        <EmptyState title="주문 정보를 불러오는 중이에요." variant="page" />
      </div>
    )
  if (detailQuery.isError)
    return isNotFoundError(detailQuery.error) ? (
      <EmptyState
        title="상품을 찾을 수 없어요."
        link="/grab"
        label="GRAB 둘러보기"
        variant="page"
      />
    ) : (
      <div className="catalog-error" role="alert">
        <EmptyState
          title="주문 정보를 불러오지 못했어요."
          description="잠시 후 다시 시도해주세요."
          variant="page"
        />
        <button
          type="button"
          className="secondary-button"
          onClick={() => void detailQuery.refetch()}
        >
          다시 시도
        </button>
      </div>
    )
  const summary = toDraftSummary(toDropDetail(detailQuery.data), draft)
  if (!summary)
    return (
      <EmptyState
        title="선택한 옵션을 찾을 수 없어요."
        link={`/drops/${draft.dropId}`}
        label="상품으로 돌아가기"
        variant="page"
      />
    )

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (createOrder.isPending || !summary.orderable) return
    const body = toOrderCreateRequest(draft, address)
    const serialized = JSON.stringify(body)
    const key =
      lastRequest?.body === serialized ? lastRequest.key : crypto.randomUUID()
    setLastRequest({ key, body: serialized })
    setError('')
    createOrder.mutate(
      { idempotencyKey: key, body },
      {
        onSuccess: (created) => {
          notify('주문을 만들었어요. 결제를 진행해주세요.')
          navigate(`/checkout/${created.orderId}`, { replace: true })
        },
        onError: (cause) => setError(createOrderErrorMessage(cause)),
      },
    )
  }

  return (
    <section className="page-section buyer-page">
      <h1>주문을 확인해 주세요</h1>
      <p className="buyer-intro">
        배송지를 입력하면 주문을 만들고 서버가 금액과 결제 마감을 확정합니다.
      </p>
      <div className="buyer-layout">
        <div className="buyer-stack">
          <section className="panel buyer-panel">
            <h2>배송지</h2>
            <form id="order-form" className="buyer-fields" onSubmit={submit}>
              <AddressFields
                address={address}
                disabled={createOrder.isPending}
                onChange={(field, value) =>
                  setAddress((current) => ({ ...current, [field]: value }))
                }
              />
            </form>
          </section>
          <section className="panel buyer-panel">
            <h2>주문 상품</h2>
            <BuyerOrderItem
              productName={summary.productName}
              optionLabel={summary.optionLabel}
              quantity={summary.quantity}
              amount={summary.itemsAmount}
              image={summary.image || undefined}
              dropId={draft.dropId}
            />
          </section>
        </div>
        <section className="panel buyer-panel buyer-summary">
          <h2>예상 결제 금액</h2>
          <BuyerOrderAmounts order={summary} />
          <p className="buyer-deadline">
            최종 금액과 결제 마감은 주문을 만든 뒤 서버 기준으로 표시합니다.
          </p>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <button
            type="submit"
            form="order-form"
            className="primary-button full"
            disabled={createOrder.isPending || !summary.orderable}
          >
            {createOrder.isPending
              ? '주문을 만드는 중…'
              : summary.orderable
                ? '주문하기'
                : '주문할 수 없는 상품'}
          </button>
        </section>
      </div>
    </section>
  )
}

function OrderCheckout({
  orderId,
  notify,
}: {
  orderId: string
  notify: (message: string) => void
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  // 토스 결과 값은 첫 렌더에서 한 번만 읽고, 결제 키가 남지 않도록 바로 주소에서 지운다.
  const [callback] = useState(() =>
    readTossCallback(window.location.search, searchParams.get('result')),
  )
  const orderQuery = useMyOrder(orderId)
  const paymentsQuery = useOrderPayments(orderId)
  const approvePayment = useApprovePayment()
  const [outcome, setOutcome] = useState<PaymentOutcome | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [opening, setOpening] = useState(false)
  // UNKNOWN이면 같은 키·본문으로 다시 확인해야 하므로 마지막 승인 요청을 남긴다(화면 메모리).
  const lastApproval = useRef<{ key: string; body: PaymentRequest } | null>(
    null,
  )
  const callbackHandled = useRef(false)
  const order = orderQuery.data
  const mismatch =
    callback?.result === 'success' &&
    order !== undefined &&
    isCallbackMismatch(callback, order)

  useEffect(() => {
    // 해시 앞 쿼리를 먼저 지워야 해시 쿼리를 바꾸는 이동이 결제 키를 다시 붙이지 않는다.
    if (window.location.search) clearTossCallback()
    if (searchParams.has('result')) setSearchParams({}, { replace: true })
  }, [searchParams, setSearchParams])

  const sendApproval = (request: { key: string; body: PaymentRequest }) => {
    lastApproval.current = request
    approvePayment.mutate(
      { orderId, idempotencyKey: request.key, body: request.body },
      {
        onSuccess: (result) => {
          const next = toPaymentOutcome(result)
          setOutcome(next)
          setError('')
          if (next.kind === 'SUCCEEDED') notify('결제를 완료했어요.')
        },
        onError: (cause) => {
          setOutcome(null)
          setError(paymentErrorMessage(cause))
        },
      },
    )
  }

  useEffect(() => {
    if (callbackHandled.current || callback?.result !== 'success' || !order)
      return
    callbackHandled.current = true
    if (isCallbackMismatch(callback, order)) return
    // 토스는 인증 후 10분 안에 승인하지 않으면 만료하므로 도착 즉시 승인한다(PAYMENT.md 1.0).
    sendApproval({
      key: crypto.randomUUID(),
      body: { paymentKey: callback.paymentKey, amount: callback.amount },
    })
    // sendApproval은 렌더마다 새로 만들어지지만 콜백 처리는 한 번만 한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [callback, order])

  if (orderQuery.isPending)
    return (
      <div role="status" aria-live="polite">
        <EmptyState title="주문을 불러오는 중이에요." variant="page" />
      </div>
    )
  if (orderQuery.isError) {
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

  const loadedOrder = orderQuery.data
  const payments = paymentsQuery.data ?? []
  const paymentsReady = paymentsQuery.isSuccess
  const awaitingCallback =
    callback?.result === 'success' && !mismatch && outcome === null && !error
  const checking =
    outcome?.kind === 'CHECKING' ||
    (paymentsReady && isPaymentChecking(loadedOrder, payments))
  const paid =
    loadedOrder.status === 'PAID' &&
    paymentsReady &&
    !needsReconciliation(payments)
  // 결제 이력을 받기 전에는 보정 필요 여부를 알 수 없으므로 결제창을 열지 않는다.
  const payable =
    paymentsReady &&
    canPay(loadedOrder, payments) &&
    !checking &&
    !awaitingCallback &&
    !approvePayment.isPending
  const failureMessage =
    outcome?.kind === 'FAILED'
      ? outcome.message
      : callback?.result === 'fail'
        ? callback.message || '결제가 완료되지 않았어요.'
        : loadedOrder.paymentStatus === 'FAILED'
          ? '최근 결제가 승인되지 않았어요.'
          : ''
  const orderName =
    loadedOrder.items.length > 1
      ? `${loadedOrder.items[0].productName} 외 ${loadedOrder.items.length - 1}건`
      : (loadedOrder.items[0]?.productName ?? loadedOrder.orderNumber)

  const pay = async () => {
    if (!payable || opening) return
    setOpening(true)
    setNotice('')
    setError('')
    try {
      await openTossPayment({
        orderId,
        orderNumber: loadedOrder.orderNumber,
        orderName,
        amount: loadedOrder.totalAmount,
      })
    } catch (cause) {
      setNotice(
        isTossUserCancel(cause)
          ? '결제창을 닫았어요. 결제 마감 전에 다시 결제할 수 있어요.'
          : cause instanceof TossConfigError
            ? '결제 설정이 없어 결제창을 열 수 없어요.'
            : '결제창을 열지 못했어요. 잠시 후 다시 시도해주세요.',
      )
    } finally {
      setOpening(false)
    }
  }

  return (
    <section className="page-section buyer-page">
      <h1>주문을 확인해 주세요</h1>
      <p className="buyer-intro">
        주문번호 {loadedOrder.orderNumber} ·{' '}
        {buyerOrderStatusText[loadedOrder.status]}
      </p>
      <div className="buyer-layout">
        <div className="buyer-stack">
          <section className="panel buyer-panel">
            <h2>배송지</h2>
            {loadedOrder.shippingAddress ? (
              <p className="buyer-note">
                받는 분 {loadedOrder.shippingAddress.recipient} ·{' '}
                {loadedOrder.shippingAddress.address1}{' '}
                {loadedOrder.shippingAddress.address2}
                {loadedOrder.shippingAddress.deliveryMemo &&
                  ` · ${loadedOrder.shippingAddress.deliveryMemo}`}
              </p>
            ) : (
              <p className="buyer-note">배송지 정보가 없어요.</p>
            )}
          </section>
          <section className="panel buyer-panel">
            <h2>주문 상품</h2>
            {loadedOrder.items.map((item, index) => (
              <BuyerOrderItem
                key={index}
                productName={item.productName}
                optionLabel={item.optionName}
                quantity={item.quantity}
                amount={item.subtotal}
              />
            ))}
          </section>
        </div>
        <section className="panel buyer-panel buyer-summary">
          <h2>결제 금액</h2>
          <BuyerOrderAmounts order={loadedOrder} />
          <p className="buyer-deadline">
            {loadedOrder.paymentExpiresAt
              ? `결제 마감: ${dateLabel(loadedOrder.paymentExpiresAt)}`
              : '결제 대기 시간이 아니에요.'}
          </p>
          <button
            type="button"
            className="primary-button full"
            disabled={!payable || opening}
            onClick={() => void pay()}
          >
            {opening
              ? '결제창을 여는 중…'
              : payable
                ? '결제하기'
                : '결제할 수 없는 주문'}
          </button>
          <small>
            토스페이먼츠 테스트 결제창이 열리며 실제 결제는 일어나지 않습니다.
          </small>
        </section>
      </div>
      <section className="panel buyer-panel buyer-result" aria-live="polite">
        <h2>결제 결과 상태</h2>
        {approvePayment.isPending || awaitingCallback ? (
          <p>
            <strong>결제 확인 중</strong> · 결제 결과를 확인하고 있어요. 창을
            닫지 마세요.
          </p>
        ) : mismatch ? (
          <p>
            <strong>결제 정보 불일치</strong> · 결제창에서 받은 주문번호나
            금액이 주문과 달라 승인하지 않았어요.
          </p>
        ) : paid ? (
          <p>
            <strong>결제 완료</strong> · 주문이 결제 완료 상태가 됐어요.
          </p>
        ) : checking ? (
          <p>
            <strong>결과 확인 중</strong> · 결제창을 다시 열지 말고 주문 상태를
            확인해주세요.
          </p>
        ) : failureMessage && loadedOrder.status === 'PAYMENT_PENDING' ? (
          <p>
            <strong>결제 실패</strong> · {failureMessage}{' '}
            {payable && '결제 마감 전이라 다시 결제할 수 있어요.'}
          </p>
        ) : loadedOrder.status !== 'PAYMENT_PENDING' ||
          (paymentsReady && !payable) ? (
          <p>
            <strong>결제 불가</strong> · 주문 상태 또는 결제 마감 시각을
            확인해주세요.
          </p>
        ) : (
          <p>
            <strong>결제 대기</strong> · 배송지와 서버 확정 금액을 확인하고
            결제해주세요.
          </p>
        )}
        {/* outcome은 승인 요청 뒤에만 생기므로 확인 중이면 마지막 요청이 남아 있다. */}
        {outcome?.kind === 'CHECKING' && (
          <button
            type="button"
            className="secondary-button"
            disabled={approvePayment.isPending}
            onClick={() => {
              if (lastApproval.current) sendApproval(lastApproval.current)
            }}
          >
            결제 결과 다시 확인
          </button>
        )}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        {notice && <p className="buyer-note">{notice}</p>}
        <Link className="secondary-button" to={`/my/orders/${orderId}`}>
          주문 상세로 이동 ↗
        </Link>
      </section>
    </section>
  )
}

export default function CheckoutPage({ authStatus, notify }: Props) {
  const { orderId } = useParams()
  const [searchParams] = useSearchParams()
  const location = useLocation()
  if (authStatus === 'loading') return <AuthLoading />
  if (authStatus === 'anonymous')
    return (
      <Navigate
        to="/login"
        state={{ from: `${location.pathname}${location.search}` }}
        replace
      />
    )
  if (orderId)
    return <OrderCheckout key={orderId} orderId={orderId} notify={notify} />
  const draft = readCheckoutDraft(searchParams)
  if (!draft)
    return (
      <EmptyState
        title="주문 정보가 올바르지 않아요."
        description="상품 화면에서 옵션과 수량을 다시 골라주세요."
        link="/grab"
        label="GRAB 둘러보기"
        variant="page"
      />
    )
  return (
    <DraftCheckout
      key={searchParams.toString()}
      draft={draft}
      notify={notify}
    />
  )
}
