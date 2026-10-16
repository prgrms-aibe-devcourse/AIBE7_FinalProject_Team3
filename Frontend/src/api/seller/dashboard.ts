import { api } from '../client'
import type { paths } from '../schema/dashboard'

type SummaryResponse =
  paths['/api/v1/seller/dashboard/summary']['get']['responses'][200]['content']['*/*']
type UpcomingDropsResponse =
  paths['/api/v1/seller/dashboard/upcoming-drops']['get']['responses'][200]['content']['*/*']
type DropStatsQuery = NonNullable<
  paths['/api/v1/seller/dashboard/drops']['get']['parameters']['query']
>
type DropStatsListResponse =
  paths['/api/v1/seller/dashboard/drops']['get']['responses'][200]['content']['*/*']

export async function getDashboardSummary(from?: string, to?: string) {
  const { data } = await api.get<SummaryResponse>('/seller/dashboard/summary', {
    params: { from, to },
  })
  if (data.success !== true || !data.data) {
    throw new Error('대시보드 요약 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

// withinMinutes는 60 또는 1440만 허용한다. 24시간 이내 일정을 보여준다(UI 문구 기준).
export async function getUpcomingDrops(eventType: 'START' | 'END') {
  const { data } = await api.get<UpcomingDropsResponse>(
    '/seller/dashboard/upcoming-drops',
    { params: { eventType, withinMinutes: 1440 } },
  )
  if (data.success !== true || !data.data) {
    throw new Error('임박 DROP 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}

export async function getDropStats(query: DropStatsQuery = {}) {
  const { data } = await api.get<DropStatsListResponse>(
    '/seller/dashboard/drops',
    { params: query },
  )
  if (
    data.success !== true ||
    !data.data ||
    !Array.isArray(data.data.content)
  ) {
    throw new Error('DROP별 통계 응답 형식이 올바르지 않습니다.')
  }
  return data.data
}
