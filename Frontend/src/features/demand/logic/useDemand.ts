import { useMutation, useQuery } from '@tanstack/react-query'
import {
  calculateDemand,
  getDemandAnalysis,
  type DemandCalculateRequest,
} from '../../../api/demand/demand'
import { demandKeys } from './queryKeys'

export function useDemandAnalysis(dropId: number, enabled: boolean) {
  return useQuery({
    queryKey: demandKeys.analysis(dropId),
    queryFn: ({ signal }) => getDemandAnalysis(dropId, signal),
    enabled,
  })
}

// 가정을 바꾼 재계산은 저장하지 않으므로 캐시에 넣지 않고 화면 상태로만 쓴다
export function useCalculateDemand(dropId: number) {
  return useMutation({
    mutationFn: (request: DemandCalculateRequest) =>
      calculateDemand(dropId, request),
  })
}
