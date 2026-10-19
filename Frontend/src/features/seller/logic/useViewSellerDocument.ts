import { useMutation } from '@tanstack/react-query'
import { viewSellerDocument } from '../../../api/seller/adminDocuments'
import type { SellerDocumentType } from '../../../api/seller/applications'

export function useViewSellerDocument() {
  return useMutation({
    mutationFn: ({
      applicationId,
      documentType,
    }: {
      applicationId: string
      documentType: SellerDocumentType
    }) => viewSellerDocument(applicationId, documentType),
  })
}
