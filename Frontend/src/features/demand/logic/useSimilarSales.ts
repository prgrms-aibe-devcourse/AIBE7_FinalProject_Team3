import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  getSimilarSales,
  getSimilarSalesForForm,
  type SimilarSalesRequest,
} from '../../../api/demand/similarSales'
import { demandKeys } from './queryKeys'
import { useDebounced } from './useDebounced'

// WISH 단계 비슷한 DROP 실적. 종료 DROP 결과라 자주 바뀌지 않는다
export function useSimilarSales(dropId: number) {
  return useQuery({
    queryKey: demandKeys.similarSales(dropId),
    queryFn: () => getSimilarSales(dropId),
    staleTime: 5 * 60_000,
  })
}

// 생성 단계. 상품명·설명이 바뀌면 요청 임베딩을 새로 만들므로 재고 추천보다 길게 기다린 뒤 조회한다
const FORM_DEBOUNCE_MS = 1000

export function useSimilarSalesForForm(request: SimilarSalesRequest | null) {
  const serialized = request ? JSON.stringify(request) : null
  const settled = useDebounced(serialized, FORM_DEBOUNCE_MS)
  return useQuery({
    queryKey: demandKeys.similarSalesForm(settled ?? ''),
    queryFn: ({ signal }) =>
      getSimilarSalesForForm(
        JSON.parse(settled!) as SimilarSalesRequest,
        signal,
      ),
    enabled: !!settled && settled === serialized,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
  })
}
