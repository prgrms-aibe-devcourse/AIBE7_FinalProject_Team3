import { apiClient } from '../../api/client'

export type MockResult = 'SUCCESS' | 'FAILURE' | 'TIMEOUT'

export type PaymentResponse = {
  paymentId: number
  orderId: number
  orderNumber: string
  amount: number
  status: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
  paidAt: string | null
}

type ApiResponse<T> = { success: boolean; data: T; message: string | null }

export async function payOrder(orderId: number, mockResult: MockResult) {
  const response = await apiClient.post<ApiResponse<PaymentResponse>>(
    `/orders/${orderId}/payments`,
    { paymentMethod: 'MOCK_CARD', mockResult },
    { headers: { 'Idempotency-Key': crypto.randomUUID() } },
  )
  return response.data.data
}
