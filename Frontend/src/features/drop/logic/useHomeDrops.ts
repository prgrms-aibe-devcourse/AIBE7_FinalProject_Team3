import { useQuery } from '@tanstack/react-query'
import { getDrops } from '../../../api/drop/drops'
import type { CardDrop } from '../../../types/drop'
import type { HomeSectionKey } from './homeSections'
import { HOME_PAGE_SIZE } from './catalogQuery'
import { toCardDrops } from './dropView'
import { dropKeys } from './queryKeys'

export type HomeDropsState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; drops: CardDrop[] }

const POPULAR_SORT: Record<
  HomeSectionKey,
  'wishCount,desc' | 'soldQuantity,desc'
> = {
  WISH: 'wishCount,desc',
  GRAB: 'soldQuantity,desc',
}

// 영역별로 독립적인 조회 키를 쓰고, 로딩·실패·재시도 상태를 각각 관리한다.
export default function useHomeDrops(section: HomeSectionKey) {
  const query = useQuery({
    queryKey: dropKeys.home(section),
    queryFn: () =>
      getDrops({
        status: section,
        sort: POPULAR_SORT[section],
        page: 0,
        size: HOME_PAGE_SIZE,
      }),
    select: (page) => toCardDrops(page.content ?? []),
  })

  const state: HomeDropsState = query.isPending
    ? { status: 'loading' }
    : query.isError
      ? { status: 'error' }
      : { status: 'ready', drops: query.data ?? [] }

  return { state, retry: () => void query.refetch() }
}
