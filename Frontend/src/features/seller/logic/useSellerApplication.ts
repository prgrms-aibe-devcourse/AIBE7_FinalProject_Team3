import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getMySellerApplication,
  submitSellerApplication,
  uploadSellerDocument,
  type SellerDocumentType,
} from '../../../api/seller/applications'
import { sellerApplicationKeys } from './applicationQueryKeys'
import { isNoApplication } from './sellerApplicationState'

// 신청이 없으면 404다. react-query 에러로 흘리지 않고 null로 돌려준다.
export function useMySellerApplication(enabled = true) {
  return useQuery({
    queryKey: sellerApplicationKeys.mine(),
    enabled,
    queryFn: async () => {
      try {
        return await getMySellerApplication()
      } catch (error) {
        if (isNoApplication(error)) return null
        throw error
      }
    },
  })
}

export function useUploadSellerDocument() {
  return useMutation({
    mutationFn: ({
      documentType,
      file,
    }: {
      documentType: SellerDocumentType
      file: File
    }) => uploadSellerDocument(documentType, file),
  })
}

export function useSubmitSellerApplication() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: submitSellerApplication,
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: sellerApplicationKeys.all,
      })
    },
  })
}
