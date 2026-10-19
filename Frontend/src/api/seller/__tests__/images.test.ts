import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { requestDropImageUploadUrl, uploadDropImage } from '../images'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const input = { fileName: '상품.png', contentType: 'image/png', fileSize: 100 }
const upload = {
  imageId: 'd254cc3c-9799-46a9-8bcf-ac66f2a76a18',
  uploadUrl: 'https://storage.example.com/upload?token=example',
  imageUrl: 'https://storage.example.com/public/image.png',
  expiresAt: '2026-10-20T12:00:00+09:00',
}

test('파일 메타데이터로 이미지 업로드 URL을 발급받는다', async () => {
  const post = vi
    .spyOn(api, 'post')
    .mockResolvedValue({ data: { success: true, data: upload } })
  await expect(requestDropImageUploadUrl(input)).resolves.toEqual(upload)
  expect(post).toHaveBeenCalledWith('/uploads/images/presigned-url', input, {
    signal: undefined,
  })
})

test('Storage PUT은 파일과 MIME을 전송하고 API 쿠키·CSRF를 보내지 않는다', async () => {
  const post = vi
    .spyOn(api, 'post')
    .mockResolvedValue({ data: { success: true, data: upload } })
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
  vi.stubGlobal('fetch', fetch)
  const file = new File(['image'], 'test.png', { type: 'image/png' })
  const signal = new AbortController().signal
  await expect(uploadDropImage(file, signal)).resolves.toEqual({
    imageId: upload.imageId,
    imageUrl: upload.imageUrl,
  })
  expect(post).toHaveBeenCalledWith(
    '/uploads/images/presigned-url',
    { fileName: file.name, contentType: file.type, fileSize: file.size },
    { signal },
  )
  expect(fetch).toHaveBeenCalledWith(upload.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': 'image/png' },
    body: file,
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    signal,
  })
})

test('Storage 실패는 거부하고 재시도마다 새 URL을 발급받는다', async () => {
  const post = vi
    .spyOn(api, 'post')
    .mockResolvedValue({ data: { success: true, data: upload } })
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(new Response('{}', { status: 403 }))
      .mockResolvedValueOnce(new Response('{}', { status: 200 })),
  )
  const file = new File(['image'], 'test.png', { type: 'image/png' })
  const signal = new AbortController().signal
  await expect(uploadDropImage(file, signal)).rejects.toThrow('업로드에 실패')
  await expect(uploadDropImage(file, signal)).resolves.toMatchObject({
    imageId: upload.imageId,
  })
  expect(post).toHaveBeenCalledTimes(2)
})

test('서명 발급 실패 또는 발급 도중 취소 시 PUT을 시작하지 않는다', async () => {
  const controller = new AbortController()
  const post = vi
    .spyOn(api, 'post')
    .mockRejectedValueOnce(new Error('실패'))
    .mockImplementationOnce(async () => {
      controller.abort()
      return { data: { success: true, data: upload } }
    })
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  const file = new File(['image'], 'test.png', { type: 'image/png' })
  await expect(uploadDropImage(file, controller.signal)).rejects.toThrow('실패')
  await expect(uploadDropImage(file, controller.signal)).rejects.toThrow()
  expect(post).toHaveBeenCalledTimes(2)
  expect(fetch).not.toHaveBeenCalled()
})

test.each(['imageId', 'uploadUrl', 'imageUrl', 'expiresAt'] as const)(
  '발급 응답의 %s 누락을 거부한다',
  async (field) => {
    vi.spyOn(api, 'post').mockResolvedValue({
      data: { success: true, data: { ...upload, [field]: undefined } },
    })
    await expect(requestDropImageUploadUrl(input)).rejects.toThrow(
      '업로드 URL 응답 형식',
    )
  },
)

test('잘못된 만료 시각과 실패 응답을 거부한다', async () => {
  vi.spyOn(api, 'post')
    .mockResolvedValueOnce({
      data: { success: true, data: { ...upload, expiresAt: 'invalid' } },
    })
    .mockResolvedValueOnce({ data: { success: false, data: upload } })
  await expect(requestDropImageUploadUrl(input)).rejects.toThrow(
    '업로드 URL 응답 형식',
  )
  await expect(requestDropImageUploadUrl(input)).rejects.toThrow(
    '업로드 URL 응답 형식',
  )
})
