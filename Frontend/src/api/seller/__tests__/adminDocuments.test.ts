import { afterEach, expect, test, vi } from 'vitest'
import { api } from '../../client'
import { viewSellerDocument } from '../adminDocuments'

afterEach(() => vi.restoreAllMocks())

test('서류 열람을 blob으로 요청한다', async () => {
  const get = vi.spyOn(api, 'get')
  const blob = new Blob(['x'], { type: 'image/png' })
  get.mockResolvedValueOnce({ data: blob })

  await expect(viewSellerDocument('app-1', 'ID_CARD')).resolves.toBe(blob)
  expect(get).toHaveBeenCalledWith(
    '/admin/seller-applications/app-1/documents/ID_CARD/view',
    { responseType: 'blob' },
  )
})
