import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  getStockRecommendation,
  type StockRecommendationRequest,
} from '../../../api/demand/stockRecommendation'
import { demandKeys } from './queryKeys'
import { useDebounced } from './useDebounced'

// 요청 내용(문자열)이 같으면 다시 조회하지 않는다. 직전 결과를 유지해 입력 중에 패널이 깜박이지 않게 한다
export function useStockRecommendation(
  request: StockRecommendationRequest | null,
) {
  const serialized = request ? JSON.stringify(request) : null
  const settled = useDebounced(serialized)
  return useQuery({
    queryKey: demandKeys.stockRecommendation(settled ?? ''),
    queryFn: ({ signal }) =>
      getStockRecommendation(
        JSON.parse(settled!) as StockRecommendationRequest,
        signal,
      ),
    enabled: !!settled && settled === serialized,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
  })
}
