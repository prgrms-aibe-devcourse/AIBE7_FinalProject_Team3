import { api } from '../client'
import type { SellerDocumentType } from './applications'

export async function viewSellerDocument(
  applicationId: string,
  documentType: SellerDocumentType,
) {
  const { data } = await api.get<Blob>(
    `/admin/seller-applications/${applicationId}/documents/${documentType}/view`,
    { responseType: 'blob' },
  )
  return data
}
