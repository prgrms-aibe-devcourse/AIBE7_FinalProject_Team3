import { useEffect, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  getStockRecommendation,
  type StockRecommendationRequest,
} from '../../../api/demand/stockRecommendation'
import { demandKeys } from './queryKeys'

// 입력이 멈춘 뒤에 조회해 타이핑마다 요청하지 않는다
const DEBOUNCE_MS = 500

function useDebounced<T>(value: T, delay: number) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay)
    return () => window.clearTimeout(timer)
  }, [value, delay])
  return debounced
}

// 요청 내용(문자열)이 같으면 다시 조회하지 않는다. 직전 결과를 유지해 입력 중에 패널이 깜박이지 않게 한다
export function useStockRecommendation(
  request: StockRecommendationRequest | null,
) {
  const serialized = request ? JSON.stringify(request) : null
  const settled = useDebounced(serialized, DEBOUNCE_MS)
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
