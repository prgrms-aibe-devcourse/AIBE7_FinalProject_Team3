import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { requestDropAiDraft } from '../dropDrafts'

afterEach(() => {
  vi.restoreAllMocks()
})

const images = [
  {
    imageId: 'd254cc3c-9799-46a9-8bcf-ac66f2a76a18',
    imageUrl: 'https://storage.example.com/images/d254cc3c.png',
  },
]

test('업로드한 이미지로 초안을 요청하고 항목별 null을 그대로 돌려준다', async () => {
  const post = vi.spyOn(api, 'post').mockResolvedValue({
    data: {
      success: true,
      data: {
        name: '블랙 니트 가디건',
        description: null,
        categoryId: 1,
        images: [{ imageId: images[0].imageId, altText: '가디건 정면' }],
      },
    },
  })
  await expect(requestDropAiDraft(images)).resolves.toEqual({
    name: '블랙 니트 가디건',
    description: null,
    categoryId: 1,
    images: [{ imageId: images[0].imageId, altText: '가디건 정면' }],
  })
  expect(post).toHaveBeenCalledWith(
    '/seller/drop-drafts',
    { images },
    { signal: undefined },
  )
})

test('형식이 다른 응답은 거부한다', async () => {
  vi.spyOn(api, 'post').mockResolvedValue({
    data: { success: true, data: { name: 1, categoryId: 'FASHION' } },
  })
  await expect(requestDropAiDraft(images)).rejects.toThrow('형식')
})
