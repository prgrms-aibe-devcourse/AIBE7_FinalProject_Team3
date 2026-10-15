import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  approveSellerApplication,
  getAdminSellerApplications,
  rejectSellerApplication,
  type AdminApplicationStatus,
} from '../../../api/seller/adminApplications'
import { adminSellerApplicationKeys } from './applicationQueryKeys'

export function useAdminSellerApplications(
  params: {
    status: AdminApplicationStatus
    page: number
    size: number
  },
  enabled = true,
) {
  return useQuery({
    queryKey: adminSellerApplicationKeys.list(params),
    enabled,
    queryFn: () => getAdminSellerApplications(params),
  })
}

export function useApproveSellerApplication() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: approveSellerApplication,
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: adminSellerApplicationKeys.all,
      })
    },
  })
}

export function useRejectSellerApplication() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      applicationId,
      reason,
    }: {
      applicationId: string
      reason: string
    }) => rejectSellerApplication(applicationId, reason),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: adminSellerApplicationKeys.all,
      })
    },
  })
}
