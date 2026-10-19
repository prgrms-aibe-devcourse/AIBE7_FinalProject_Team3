import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { requestDropImageUploadUrl } from '../images'

afterEach(() => vi.restoreAllMocks())

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
  expect(post).toHaveBeenCalledWith('/uploads/images/presigned-url', input)
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
