import { api } from '../client'
import type { components, paths } from '../schema/drop'
import { putToSignedUrl, readSignedImageUpload } from '../storage/upload'

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
  return readSignedImageUpload(data)
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
  await putToSignedUrl(upload.uploadUrl, file, signal)
  return { imageId: upload.imageId, imageUrl: upload.imageUrl }
}
