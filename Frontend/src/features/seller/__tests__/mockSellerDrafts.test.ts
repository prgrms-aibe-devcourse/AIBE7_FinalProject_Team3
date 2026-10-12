import { describe, expect, it } from 'vitest'
import {
  existingSellerDraft,
  imageError,
  moveImage,
} from '../mock/mockSellerDrafts'

describe('판매자 DROP 초안 이미지', () => {
  it('기존 DRAFT 이미지는 GALLERY로 편집하고 공개 DROP은 제외한다', () => {
    expect(existingSellerDraft('401')?.gallery).toHaveLength(1)
    expect(existingSellerDraft('401')?.detail).toHaveLength(0)
    expect(existingSellerDraft('101')).toBeUndefined()
  })

  it('합계 개수·MIME·크기를 검증한다', () => {
    const file = (type: string, size: number) => ({ type, size }) as File
    expect(imageError(file('image/png', 100), 10)).toMatch(/10장/)
    expect(imageError(file('image/gif', 100), 0)).toMatch(/JPEG/)
    expect(imageError(file('image/webp', 5 * 1024 * 1024 + 1), 0)).toMatch(
      /5MB/,
    )
    expect(imageError(file('image/jpeg', 5 * 1024 * 1024), 9)).toBe('')
  })

  it('한 용도의 순서를 바꿔도 원본 목록은 유지한다', () => {
    const images = [
      { id: 'a', url: 'a', altText: '첫째' },
      { id: 'b', url: 'b', altText: '둘째' },
    ]
    expect(moveImage(images, 0, 1).map((image) => image.id)).toEqual(['b', 'a'])
    expect(images.map((image) => image.id)).toEqual(['a', 'b'])
    expect(moveImage(images, 0, -1)).toBe(images)
  })
})
