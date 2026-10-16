import { useQuery } from '@tanstack/react-query'
import { getSellerDropStocks } from '../../../api/seller/drops'
import { sellerDropStocksKeys } from './dashboardQueryKeys'

export function useSellerDropStocks(dropId: number | null) {
  return useQuery({
    queryKey: dropId
      ? sellerDropStocksKeys.detail(dropId)
      : sellerDropStocksKeys.all,
    queryFn: () => getSellerDropStocks(dropId as number),
    enabled: dropId !== null,
  })
}
