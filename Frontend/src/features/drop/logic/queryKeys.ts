export type DropListParams = {
  status: 'WISH' | 'GRAB'
  categoryId?: number
  keyword?: string
  soldOut?: boolean
  sort: string
  page: number
  size: number
}

// 목록 조건을 키 값으로 펼쳐 객체 동일성과 무관하게 같은 조회를 같은 키로 묶는다.
export const dropKeys = {
  all: ['drops'] as const,
  home: (status: 'WISH' | 'GRAB') => ['drops', 'home', status] as const,
  list: (params: DropListParams) =>
    [
      'drops',
      'list',
      params.status,
      params.categoryId ?? null,
      params.keyword ?? null,
      params.soldOut ?? null,
      params.sort,
      params.page,
      params.size,
    ] as const,
  detail: (dropId: number) => ['drops', 'detail', dropId] as const,
  stocks: (dropId: number) => ['drops', 'detail', dropId, 'stocks'] as const,
  // 재고 알림 재조회는 상세·재고만 대상이라(REALTIME_STOCK.md 4절) 상세 키 접두어 밖에 둔다
  related: (dropId: number) => ['drops', 'related', dropId] as const,
}

export const categoryKeys = {
  all: ['categories'] as const,
}
