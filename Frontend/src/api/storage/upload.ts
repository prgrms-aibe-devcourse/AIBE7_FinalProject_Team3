// Storage 서명 URL 업로드(IMAGE_UPLOAD 1.1 DROP 이미지, 1.2 프로필 이미지). DROP·프로필이 함께 쓴다(GR-132 M00-06).
// 발급 요청은 경로·권한이 달라 각 도메인에 두고, 응답 검사와 Storage PUT만 여기서 맡는다.

type UploadUrlBody = {
  success?: boolean
  data?: {
    imageId?: string
    uploadUrl?: string
    imageUrl?: string
    expiresAt?: string
  }
}

export type SignedImageUpload = {
  imageId: string
  uploadUrl: string
  imageUrl: string
  expiresAt: string
}

// Storage 응답은 공통 오류 형식이 아니라 상태 코드만 남긴다(연결 실패는 0). 화면은 API 오류와 구분해 문구를 고른다.
export class StorageUploadError extends Error {
  readonly status: number

  constructor(status: number) {
    super('이미지 업로드에 실패했어요. 다시 시도해주세요.')
    this.name = 'StorageUploadError'
    this.status = status
  }
}

export function readSignedImageUpload(body: UploadUrlBody): SignedImageUpload {
  const upload = body.data
  if (
    body.success !== true ||
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

export async function putToSignedUrl(
  uploadUrl: string,
  file: File,
  signal: AbortSignal,
) {
  // Storage 서명 URL에는 API 인증 쿠키·CSRF 헤더를 보내지 않는다.
  const response = await fetch(uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': file.type },
    body: file,
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    signal,
  }).catch((error: unknown) => {
    // fetch의 연결 실패는 TypeError다. 취소(AbortError)는 그대로 던져 호출부가 무시할 수 있게 한다.
    if (error instanceof TypeError) throw new StorageUploadError(0)
    throw error
  })
  if (!response.ok) throw new StorageUploadError(response.status)
}
