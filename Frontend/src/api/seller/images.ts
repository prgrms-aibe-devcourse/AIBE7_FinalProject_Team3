import { api } from '../client'
import type { components, paths } from '../schema/drop'

type UploadInput = components['schemas']['ImageUploadUrlRequest']
type UploadResponse =
  paths['/api/v1/uploads/images/presigned-url']['post']['responses'][200]['content']['*/*']

export async function requestDropImageUploadUrl(
  input: UploadInput,
  signal?: AbortSignal,
) {
  const { data } = await api.post<UploadResponse>(
    '/uploads/images/presigned-url',
    input,
    { signal },
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

export async function uploadDropImage(file: File, signal: AbortSignal) {
  const upload = await requestDropImageUploadUrl(
    {
      fileName: file.name,
      contentType: file.type,
      fileSize: file.size,
    },
    signal,
  )
  signal.throwIfAborted()
  // Storage 서명 URL에는 API 인증 쿠키·CSRF 헤더를 보내지 않는다.
  const response = await fetch(upload.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': file.type },
    body: file,
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    signal,
  })
  if (!response.ok)
    throw new Error('이미지 업로드에 실패했어요. 다시 시도해주세요.')
  return { imageId: upload.imageId, imageUrl: upload.imageUrl }
}
