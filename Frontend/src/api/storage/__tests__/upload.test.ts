import { afterEach, expect, test, vi } from 'vitest'
import { putToSignedUrl, StorageUploadError } from '../upload'

// Storage 서명 URL 업로드(GR-132 M00-06). 응답 검사·PUT 옵션은 DROP(images.test.ts)·프로필(me.test.ts)에서도 확인한다.

afterEach(() => {
  vi.unstubAllGlobals()
})

const file = new File(['image'], 'image.png', { type: 'image/png' })
const url = 'https://storage.example.com/upload?token=signed'

test('2xx가 아니면 상태 코드를 담은 StorageUploadError를 던진다', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(new Response('{}', { status: 400 })),
  )
  const error = await putToSignedUrl(
    url,
    file,
    new AbortController().signal,
  ).catch((caught: unknown) => caught)
  expect(error).toBeInstanceOf(StorageUploadError)
  expect(error).toMatchObject({ status: 400 })
})

test('연결 실패(TypeError)는 상태 코드 0인 StorageUploadError로 바꾼다', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
  )
  await expect(
    putToSignedUrl(url, file, new AbortController().signal),
  ).rejects.toEqual(new StorageUploadError(0))
})

test('취소(AbortError)는 바꾸지 않고 그대로 던진다', async () => {
  const abort = new DOMException('aborted', 'AbortError')
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(abort))
  await expect(
    putToSignedUrl(url, file, new AbortController().signal),
  ).rejects.toBe(abort)
})
