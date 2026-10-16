import axios from 'axios'

type ErrorBody = { error?: { code?: string; message?: string } }

function errorCode(error: unknown): string | undefined {
  if (!axios.isAxiosError(error)) return undefined
  return (error.response?.data as ErrorBody | undefined)?.error?.code
}

export function sellerOrderErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return '처리하지 못했어요. 잠시 후 다시 시도해주세요.'
  }
  const status = error.response?.status
  if (status === 401 || status === 403) {
    return '판매자 주문을 볼 권한이 없어요.'
  }
  if (status === 404) {
    return '요청한 주문 또는 DROP을 찾을 수 없어요.'
  }
  return '처리하지 못했어요. 잠시 후 다시 시도해주세요.'
}

// 배송 준비·송장 등록/수정 액션 전용 메시지(GR-106). 조회 오류와 원인이 달라 구분한다.
export function shipmentErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return '처리하지 못했어요. 잠시 후 다시 시도해주세요.'
  }
  const status = error.response?.status
  const code = errorCode(error)
  if (status === 401 || status === 403) {
    return '판매자 권한으로 로그인한 뒤 이용할 수 있어요.'
  }
  if (status === 404) {
    return '주문을 찾을 수 없어요. 목록을 다시 확인해주세요.'
  }
  if (code === 'PAYMENT_CANCELLATION_UNKNOWN') {
    return '결제 취소를 확인하는 중이라 지금은 처리할 수 없어요.'
  }
  if (code === 'ORDER_STATUS_CONFLICT' || code === 'INVALID_STATE_TRANSITION') {
    return '주문 상태가 바뀌었어요. 목록을 다시 확인해주세요.'
  }
  if (code === 'DUPLICATE_IDEMPOTENCY_KEY') {
    return '이전 요청과 내용이 달라요. 다시 시도해주세요.'
  }
  return '배송 작업을 처리하지 못했어요. 다시 시도해주세요.'
}
