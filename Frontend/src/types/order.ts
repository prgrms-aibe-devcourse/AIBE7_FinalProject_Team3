import type { Drop } from './drop'

export type Order = {
  id: string
  apiOrderId?: number
  drop: Drop
  optionLabel: string
  quantity: number
  total: number
  status: 'PAYMENT_PENDING' | 'PAID' | 'PAYMENT_FAILED' | 'PAYMENT_UNKNOWN'
  paymentId?: number
}
