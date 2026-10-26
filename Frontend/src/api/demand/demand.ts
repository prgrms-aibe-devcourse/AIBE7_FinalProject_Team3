import { api } from '../client'
import type { components } from '../schema/demand'

type Schemas = components['schemas']

export type DemandAnalysis = Schemas['DemandAnalysisResponse']
export type DemandCalculateRequest = Schemas['DemandCalculateRequest']
export type DemandPlan = Schemas['DemandPlanResponse']
export type DemandPlanSummary = Schemas['DemandPlanSummaryResponse']
export type DemandDecisionRequest = Schemas['DemandPlanDecisionRequest']

const PLAN_STATUSES = ['DRAFT', 'ACCEPTED', 'MODIFIED', 'REJECTED']

// 화면이 숫자를 다시 계산하지 않으므로 표시에 필요한 뼈대만 확인한다(DEMAND.md 2.1)
function validAnalysis(analysis: DemandAnalysis | undefined, dropId: number) {
  return (
    !!analysis &&
    analysis.dropId === dropId &&
    !!analysis.demand &&
    !!analysis.conversion &&
    !!analysis.assumptions &&
    !!analysis.plan &&
    Array.isArray(analysis.plan.options) &&
    Array.isArray(analysis.dataGaps)
  )
}

function validPlan(plan: DemandPlan | undefined) {
  return (
    !!plan &&
    typeof plan.planId === 'string' &&
    PLAN_STATUSES.includes(plan.status ?? '') &&
    validAnalysis(plan.analysis, plan.dropId ?? -1)
  )
}

export async function getDemandAnalysis(dropId: number, signal?: AbortSignal) {
  const { data } = await api.get<Schemas['ApiResponseDemandAnalysisResponse']>(
    `/seller/drops/${dropId}/demand-analysis`,
    { signal },
  )
  if (data.success !== true || !validAnalysis(data.data, dropId))
    throw new Error('수요 분석 응답 형식이 올바르지 않습니다.')
  return data.data!
}

export async function calculateDemand(
  dropId: number,
  request: DemandCalculateRequest,
) {
  const { data } = await api.post<Schemas['ApiResponseDemandAnalysisResponse']>(
    `/seller/drops/${dropId}/demand-analysis/calculate`,
    request,
  )
  if (data.success !== true || !validAnalysis(data.data, dropId))
    throw new Error('수요 분석 재계산 응답 형식이 올바르지 않습니다.')
  return data.data!
}

export async function saveDemandPlan(
  dropId: number,
  request: DemandCalculateRequest,
) {
  const { data } = await api.post<Schemas['ApiResponseDemandPlanResponse']>(
    `/seller/drops/${dropId}/demand-plans`,
    request,
  )
  if (data.success !== true || !validPlan(data.data))
    throw new Error('판매 계획 저장 응답 형식이 올바르지 않습니다.')
  return data.data!
}

export async function getDemandPlans(
  dropId: number,
  page: number,
  size: number,
  signal?: AbortSignal,
) {
  const { data } = await api.get<
    Schemas['ApiResponsePageResponseDemandPlanSummaryResponse']
  >(`/seller/drops/${dropId}/demand-plans`, {
    params: { page, size },
    signal,
  })
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content) ||
    data.data.content.some(
      (plan) =>
        typeof plan.planId !== 'string' ||
        !PLAN_STATUSES.includes(plan.status ?? ''),
    ) ||
    typeof data.data.hasNext !== 'boolean'
  )
    throw new Error('판매 계획 목록 응답 형식이 올바르지 않습니다.')
  return data.data
}

export async function getDemandPlan(planId: string, signal?: AbortSignal) {
  const { data } = await api.get<Schemas['ApiResponseDemandPlanResponse']>(
    `/seller/demand-plans/${encodeURIComponent(planId)}`,
    { signal },
  )
  if (data.success !== true || !validPlan(data.data))
    throw new Error('판매 계획 응답 형식이 올바르지 않습니다.')
  return data.data!
}

export async function decideDemandPlan(
  planId: string,
  request: DemandDecisionRequest,
) {
  const { data } = await api.post<Schemas['ApiResponseDemandPlanResponse']>(
    `/seller/demand-plans/${encodeURIComponent(planId)}/decision`,
    request,
  )
  if (
    data.success !== true ||
    !validPlan(data.data) ||
    data.data!.status !== request.status
  )
    throw new Error('판매 계획 결정 응답 형식이 올바르지 않습니다.')
  return data.data!
}
