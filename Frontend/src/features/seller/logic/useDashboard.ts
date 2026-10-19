import { useQuery } from '@tanstack/react-query'
import {
  getDashboardSummary,
  getDropStats,
  getUpcomingDrops,
} from '../../../api/seller/dashboard'
import type { DropStatus } from '../../../types/drop'
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

export function useDropStats(
  query: {
    status?: DropStatus
    dropId?: number
    page?: number
    size?: number
  },
  enabled = true,
) {
  return useQuery({
    queryKey: dashboardKeys.dropStats(query),
    queryFn: () => getDropStats(query),
    enabled,
  })
}
