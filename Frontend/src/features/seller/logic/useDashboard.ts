import { useQuery } from '@tanstack/react-query'
import {
  getDashboardSummary,
  getUpcomingDrops,
} from '../../../api/seller/dashboard'
import { dashboardKeys } from './dashboardQueryKeys'

// 요약과 임박 조회는 서로 독립적으로 실패 처리한다(부분 실패 허용).
export function useDashboardSummary(from?: string, to?: string) {
  return useQuery({
    queryKey: dashboardKeys.summary(from, to),
    queryFn: () => getDashboardSummary(from, to),
  })
}

export function useUpcomingDrops(eventType: 'START' | 'END') {
  return useQuery({
    queryKey: dashboardKeys.upcoming(eventType),
    queryFn: () => getUpcomingDrops(eventType),
  })
}
