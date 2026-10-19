import { useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createSellerDrop,
  getSellerDropDetail,
  publishSellerDrop,
  updateSellerDrop,
  type SellerDropDraftInput,
} from '../../../api/seller/drops'
import { dropKeys } from '../../drop/logic/queryKeys'
import { dashboardKeys } from './dashboardQueryKeys'
import { sellerDropKeys } from './sellerDropQueryKeys'
import { toSellerDraft } from './sellerDropForm'

export function useSellerDropForm(dropId: number, enabled: boolean) {
  return useQuery({
    queryKey: sellerDropKeys.detail(dropId),
    queryFn: ({ signal }) => getSellerDropDetail(dropId, signal),
    select: (detail) =>
      detail.status === 'DRAFT' ? toSellerDraft(detail) : null,
    enabled,
  })
}

export function useSaveSellerDrop(dropId: number | null) {
  const savedId = useRef(dropId)
  const queryClient = useQueryClient()
  const invalidate = () => {
    void queryClient.invalidateQueries({
      queryKey: sellerDropKeys.all,
      refetchType: 'none',
    })
    void queryClient.invalidateQueries({ queryKey: dashboardKeys.all })
  }
  const mutation = useMutation({
    mutationFn: async ({
      input,
      publish,
    }: {
      input: SellerDropDraftInput
      publish: boolean
    }) => {
      const saved = savedId.current
        ? await updateSellerDrop(savedId.current, input)
        : await createSellerDrop(input)
      // 공개 실패 뒤 재시도해도 이미 생성한 DRAFT를 다시 만들지 않는다.
      savedId.current = saved.dropId
      invalidate()
      if (publish) {
        await publishSellerDrop(saved.dropId)
        invalidate()
        void queryClient.invalidateQueries({ queryKey: dropKeys.all })
      }
      return saved.dropId
    },
  })
  return { ...mutation, getSavedId: () => savedId.current }
}
