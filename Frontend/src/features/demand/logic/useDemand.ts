import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  calculateDemand,
  decideDemandPlan,
  getDemandAnalysis,
  getDemandPlan,
  getDemandPlans,
  saveDemandPlan,
  type DemandCalculateRequest,
  type DemandDecisionRequest,
} from '../../../api/demand/demand'
import { demandKeys } from './queryKeys'

export const DEMAND_PLAN_PAGE_SIZE = 10

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

export function useDemandPlans(dropId: number, page: number, enabled: boolean) {
  return useQuery({
    queryKey: demandKeys.planList(dropId, page),
    queryFn: ({ signal }) =>
      getDemandPlans(dropId, page, DEMAND_PLAN_PAGE_SIZE, signal),
    enabled,
  })
}

export function useDemandPlan(planId: string | null) {
  return useQuery({
    queryKey: demandKeys.plan(planId ?? ''),
    queryFn: ({ signal }) => getDemandPlan(planId!, signal),
    enabled: !!planId,
  })
}

export function useSaveDemandPlan(dropId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: DemandCalculateRequest) =>
      saveDemandPlan(dropId, request),
    onSuccess: (plan) =>
      queryClient.setQueryData(demandKeys.plan(plan.planId!), plan),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: demandKeys.plans(dropId) }),
  })
}

// 실패해도 다른 탭의 결정이 반영됐을 수 있어 목록·상세를 다시 조회한다
export function useDecideDemandPlan(dropId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      planId,
      request,
    }: {
      planId: string
      request: DemandDecisionRequest
    }) => decideDemandPlan(planId, request),
    onSuccess: (plan) =>
      queryClient.setQueryData(demandKeys.plan(plan.planId!), plan),
    onSettled: (_data, _error, { planId }) => {
      void queryClient.invalidateQueries({ queryKey: demandKeys.plans(dropId) })
      void queryClient.invalidateQueries({ queryKey: demandKeys.plan(planId) })
    },
  })
}
