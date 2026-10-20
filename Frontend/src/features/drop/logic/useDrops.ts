import { useQuery } from '@tanstack/react-query'
import { getDropDetail, getDrops, getDropStocks } from '../../../api/drop/drops'
import type { DropListParams } from './queryKeys'
import { dropKeys } from './queryKeys'

// 조건이 없으면(null) 호출하지 않는다. 조건 판정은 resolveListParams가 맡는다.
export function useDropList(params: DropListParams | null) {
  return useQuery({
    queryKey: params ? dropKeys.list(params) : dropKeys.all,
    queryFn: ({ signal }) => getDrops(params ?? {}, signal),
    enabled: params !== null,
    // 화면·온라인 복귀 조회는 SSE 제어기의 2초 제한으로 병합한다.
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

export function useDropDetail(dropId: number) {
  return useQuery({
    queryKey: dropKeys.detail(dropId),
    queryFn: ({ signal }) => getDropDetail(dropId, signal),
    enabled: Number.isSafeInteger(dropId) && dropId > 0,
  })
}

export function useDropStocks(dropId: number) {
  return useQuery({
    queryKey: dropKeys.stocks(dropId),
    queryFn: ({ signal }) => getDropStocks(dropId, signal),
    enabled: Number.isSafeInteger(dropId) && dropId > 0,
  })
}
