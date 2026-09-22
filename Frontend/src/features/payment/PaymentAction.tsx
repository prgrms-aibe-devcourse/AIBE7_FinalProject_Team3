import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import type { Order } from '../../types/order'
import { payOrder, type MockResult } from './api'

type Props = {
  order: Order
  updateOrder: (id: string, changes: Partial<Order>) => void
  notify: (message: string) => void
}

const statusMessage = {
  SUCCESS: '결제가 완료됐어요.',
  FAILURE: '결제에 실패했어요. 다시 시도할 수 있습니다.',
  TIMEOUT: '결제 결과를 확인 중이에요.',
} satisfies Record<MockResult, string>

export default function PaymentAction({ order, updateOrder, notify }: Props) {
  const [mockResult, setMockResult] = useState<MockResult>('SUCCESS')
  const mutation = useMutation({
    mutationFn: async () => {
      if (order.apiOrderId) return payOrder(order.apiOrderId, mockResult)
      await new Promise((resolve) => window.setTimeout(resolve, 350))
      return {
        paymentId: Date.now(),
        status:
          mockResult === 'SUCCESS'
            ? ('SUCCEEDED' as const)
            : mockResult === 'FAILURE'
              ? ('FAILED' as const)
              : ('UNKNOWN' as const),
      }
    },
    onSuccess: (payment) => {
      updateOrder(order.id, {
        paymentId: payment.paymentId,
        status:
          payment.status === 'SUCCEEDED'
            ? 'PAID'
            : payment.status === 'FAILED'
              ? 'PAYMENT_FAILED'
              : 'PAYMENT_UNKNOWN',
      })
      notify(statusMessage[mockResult])
    },
    onError: () => notify('결제 요청에 실패했습니다. 잠시 후 다시 시도해주세요.'),
  })

  if (order.status === 'PAID') return <span className="payment-complete">결제 완료</span>
  if (order.status === 'PAYMENT_UNKNOWN') {
    return <span className="payment-pending">결제 확인 중</span>
  }

  return (
    <div className="payment-action">
      <select
        aria-label="Mock 결제 결과"
        value={mockResult}
        onChange={(event) => setMockResult(event.target.value as MockResult)}
      >
        <option value="SUCCESS">성공 결제</option>
        <option value="FAILURE">실패 테스트</option>
        <option value="TIMEOUT">타임아웃 테스트</option>
      </select>
      <button
        className="primary-button"
        disabled={mutation.isPending}
        onClick={() => mutation.mutate()}
      >
        {mutation.isPending
          ? '결제 중…'
          : order.status === 'PAYMENT_FAILED'
            ? '다시 결제'
            : '결제하기'}
      </button>
    </div>
  )
}
