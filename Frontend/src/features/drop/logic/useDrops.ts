import { useQuery } from '@tanstack/react-query'
import {
  getDropDetail,
  getDrops,
  getDropStocks,
  getRelatedDrops,
} from '../../../api/drop/drops'
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

// dataUpdatedAt을 수신 시각으로 쓰므로 serverTime을 유지한 채 setQueryData로 상세를 갱신하지 않는다.
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

// 관련 DROP 추천은 상세가 WISH 중이거나 품절·종료일 때만 그려지며, 자주 바뀌지 않아 1분 동안 다시 조회하지 않는다
export function useRelatedDrops(dropId: number) {
  return useQuery({
    queryKey: dropKeys.related(dropId),
    queryFn: ({ signal }) => getRelatedDrops(dropId, signal),
    enabled: Number.isSafeInteger(dropId) && dropId > 0,
    staleTime: 60_000,
    retry: false,
  })
}
