import { api } from '../client'
import type { components } from '../schema/demand'

type Schemas = components['schemas']

export type DemandAnalysis = Schemas['DemandAnalysisResponse']
export type DemandCalculateRequest = Schemas['DemandCalculateRequest']

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

export async function getDemandAnalysis(dropId: number, signal?: AbortSignal) {
  const { data } = await api.get<Schemas['ApiResponseDemandAnalysisResponse']>(
    `/seller/drops/${dropId}/demand-analysis`,
    { signal },
  )
  if (data.success !== true || !validAnalysis(data.data, dropId))
    throw new Error('수요 분석 응답 형식이 올바르지 않습니다.')
  return data.data!
}

// 가정을 바꿔 다시 계산한다. 서버는 저장하지 않는다(DEMAND.md 2.2)
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
