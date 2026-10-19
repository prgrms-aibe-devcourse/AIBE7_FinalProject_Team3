import axios from 'axios'

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
