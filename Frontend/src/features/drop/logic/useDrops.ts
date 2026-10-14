import { useQuery } from '@tanstack/react-query'
import { getDropDetail, getDrops, getDropStocks } from '../../../api/drop/drops'
import type { CatalogQuery } from './catalog'
import { toDropListParams } from './catalogQuery'
import { dropKeys } from './queryKeys'

type Category = { categoryId?: number; name?: string }

export function useDropList(
  query: CatalogQuery,
  categories: readonly Category[],
  size: number,
) {
  const params = toDropListParams(query, categories, size)
  return useQuery({
    queryKey: dropKeys.list(params),
    queryFn: () => getDrops(params),
  })
}

export function useDropDetail(dropId: number) {
  return useQuery({
    queryKey: dropKeys.detail(dropId),
    queryFn: () => getDropDetail(dropId),
    enabled: Number.isSafeInteger(dropId) && dropId > 0,
  })
}

export function useDropStocks(dropId: number) {
  return useQuery({
    queryKey: dropKeys.stocks(dropId),
    queryFn: () => getDropStocks(dropId),
    enabled: Number.isSafeInteger(dropId) && dropId > 0,
  })
}
