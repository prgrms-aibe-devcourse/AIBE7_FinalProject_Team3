import { useEffect, useRef } from 'react'
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
  useEffect(
    () => () => {
      if (!savedId.current) return
      const queryKey = sellerDropKeys.detail(savedId.current)
      if (queryClient.getQueryState(queryKey)?.status === 'error')
        queryClient.removeQueries({ queryKey, exact: true })
    },
    [queryClient],
  )
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
      const queryKey = sellerDropKeys.detail(saved.dropId)
      await queryClient.cancelQueries({ queryKey, exact: true })
      await queryClient.prefetchQuery({
        queryKey,
        queryFn: ({ signal }) => getSellerDropDetail(saved.dropId, signal),
      })
      // 편집 중인 폼은 유지하고, 실패한 캐시는 이탈 후 다시 사용하지 않는다.
      if (queryClient.getQueryState(queryKey)?.status === 'error')
        queryClient.removeQueries({ queryKey, exact: true, type: 'inactive' })
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
