// GET /api/v1/seller/dashboard/drops?status=ENDED (GR-43) 응답 형태.
// salesAmount는 PAID 이후 상태 주문의 total_amount 합이며 배송비를 포함한다.
// CANCELED·EXPIRED 주문은 집계에서 제외한다.
export type SellerDropStat = {
  dropId: number
  name: string
  status: 'ENDED'
  saleEndsAt: string
  orderCount: number
  soldStock: number
  salesAmount: number
}

const daysAgo = (days: number) =>
  new Date(Date.now() - days * 86_400_000).toISOString()

// 종료 최근순. 서버 기본 정렬과 같다.
export const endedDrops: SellerDropStat[] = [
  {
    dropId: 301,
    name: '가을 코듀로이 셔츠',
    status: 'ENDED',
    saleEndsAt: daysAgo(3),
    orderCount: 14,
    soldStock: 18,
    salesAmount: 1806000,
  },
  {
    dropId: 302,
    name: '손으로 빚은 머그 · 2차',
    status: 'ENDED',
    saleEndsAt: daysAgo(11),
    orderCount: 23,
    soldStock: 31,
    salesAmount: 812000,
  },
  {
    dropId: 303,
    name: '리버시블 버킷햇',
    status: 'ENDED',
    saleEndsAt: daysAgo(24),
    orderCount: 6,
    soldStock: 7,
    salesAmount: 340000,
  },
]
