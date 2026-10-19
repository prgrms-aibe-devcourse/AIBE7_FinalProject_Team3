import { api } from '../client'
import type { components, paths } from '../schema/drop'

type UploadInput = components['schemas']['ImageUploadUrlRequest']
type UploadResponse =
  paths['/api/v1/uploads/images/presigned-url']['post']['responses'][200]['content']['*/*']

export async function requestDropImageUploadUrl(input: UploadInput) {
  const { data } = await api.post<UploadResponse>(
    '/uploads/images/presigned-url',
    input,
  )
  const upload = data.data
  if (
    data.success !== true ||
    !upload?.imageId ||
    !upload.uploadUrl ||
    !upload.imageUrl ||
    !upload.expiresAt ||
    !Number.isFinite(Date.parse(upload.expiresAt))
  ) {
    throw new Error('이미지 업로드 URL 응답 형식이 올바르지 않습니다.')
  }
  return {
    imageId: upload.imageId,
    uploadUrl: upload.uploadUrl,
    imageUrl: upload.imageUrl,
    expiresAt: upload.expiresAt,
  }
}
