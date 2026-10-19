import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelSellerDrop,
  getSellerDropDetail,
  getSellerDropWishCount,
  getSellerDrops,
  publishSellerDrop,
  type SellerDropListParams,
} from '../../../api/seller/drops'
import { sellerDropKeys } from './sellerDropQueryKeys'
import { dashboardKeys } from './dashboardQueryKeys'
import { dropKeys } from '../../drop/logic/queryKeys'

export function useSellerDrops(params: SellerDropListParams, enabled: boolean) {
  return useQuery({
    queryKey: sellerDropKeys.list(params),
    queryFn: ({ signal }) => getSellerDrops(params, signal),
    enabled,
  })
}

export function useSellerDropDetail(dropId: number, enabled: boolean) {
  return useQuery({
    queryKey: sellerDropKeys.detail(dropId),
    queryFn: ({ signal }) => getSellerDropDetail(dropId, signal),
    enabled,
  })
}

export function useSellerDropWishCount(dropId: number, enabled: boolean) {
  return useQuery({
    queryKey: sellerDropKeys.wishCount(dropId),
    queryFn: () => getSellerDropWishCount(dropId),
    enabled,
  })
}

export function useSellerDropAction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ dropId, reason }: { dropId: number; reason?: string }) =>
      reason === undefined
        ? publishSellerDrop(dropId)
        : cancelSellerDrop(dropId, reason),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: sellerDropKeys.all })
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.all })
      void queryClient.invalidateQueries({ queryKey: dropKeys.all })
    },
  })
}
