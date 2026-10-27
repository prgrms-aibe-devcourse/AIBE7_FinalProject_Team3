import { useMutation, useQuery } from '@tanstack/react-query'
import {
  calculateDemand,
  getDemandAnalysis,
  requestDemandExplanation,
  type DemandCalculateRequest,
} from '../../../api/demand/demand'
import { demandKeys } from './queryKeys'

// WISH 수는 분 단위로 크게 바뀌지 않는다. 창에 돌아올 때마다 다시 조회하면 판매자가 다시 계산한 결과가
// 곧바로 초기화되므로(DemandReferenceCard) 1분 동안은 같은 결과를 쓴다
const ANALYSIS_STALE_TIME = 60_000

export function useDemandAnalysis(dropId: number, enabled: boolean) {
  return useQuery({
    queryKey: demandKeys.analysis(dropId),
    queryFn: ({ signal }) => getDemandAnalysis(dropId, signal),
    enabled,
    staleTime: ANALYSIS_STALE_TIME,
  })
}

// 가정을 바꾼 재계산은 저장하지 않으므로 캐시에 넣지 않고 화면 상태로만 쓴다
export function useCalculateDemand(dropId: number) {
  return useMutation({
    mutationFn: (request: DemandCalculateRequest) =>
      calculateDemand(dropId, request),
  })
}

// 판매자가 "AI 설명 보기"를 누를 때만 부른다. 화면에 보이는 결과의 가정을 그대로 보낸다
export function useDemandExplanation(dropId: number) {
  return useMutation({
    mutationFn: (request: DemandCalculateRequest) =>
      requestDemandExplanation(dropId, request),
  })
}
