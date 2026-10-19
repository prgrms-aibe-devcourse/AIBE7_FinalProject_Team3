export const dashboardDropStatuses = [
  'DRAFT',
  'WISH',
  'GRAB',
  'ENDED',
  'CANCELED',
] as const

export const dashboardOrderStatuses = [
  'PAYMENT_PENDING',
  'PAID',
  'PREPARING',
  'SHIPPED',
  'DELIVERED',
  'EXPIRED',
  'CANCELED',
] as const

export const dashboardPaymentStatuses = [
  'PENDING',
  'SUCCEEDED',
  'FAILED',
  'UNKNOWN',
  'CANCELED',
] as const
