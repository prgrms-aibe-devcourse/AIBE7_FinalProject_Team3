import { expect, test } from 'vitest'
import { imageError, moveImage } from '../logic/dropImages'

test('용도별 이미지 상한은 GALLERY 5장·DETAIL 15장이다', () => {
  const file = new File(['image'], 'image.png', { type: 'image/png' })
  expect(imageError(file, 4, 'GALLERY')).toBe('')
  expect(imageError(file, 5, 'GALLERY')).toMatch(/GALLERY.*5장/)
  expect(imageError(file, 14, 'DETAIL')).toBe('')
  expect(imageError(file, 15, 'DETAIL')).toMatch(/DETAIL.*15장/)
})

test('파일 크기·MIME·파일명을 검증한다', () => {
  expect(
    imageError(new File([], 'empty.png', { type: 'image/png' }), 0, 'GALLERY'),
  ).toMatch(/빈 파일/)
  expect(
    imageError(
      new File(['image'], 'image.gif', { type: 'image/gif' }),
      0,
      'GALLERY',
    ),
  ).toMatch(/JPEG/)
  const file = (size: number) =>
    new File([new Uint8Array(size)], 'image.webp', { type: 'image/webp' })
  expect(imageError(file(5 * 1024 * 1024), 0, 'DETAIL')).toBe('')
  expect(imageError(file(5 * 1024 * 1024 + 1), 0, 'DETAIL')).toMatch(/5MB/)
  expect(
    imageError(
      new File(['image'], 'a'.repeat(256), { type: 'image/png' }),
      0,
      'GALLERY',
    ),
  ).toMatch(/255자/)
})

test('한 용도의 순서를 바꿔도 원본 목록은 유지한다', () => {
  const images = [
    { id: 'a', url: 'a', altText: '첫째' },
    { id: 'b', url: 'b', altText: '둘째' },
  ]
  expect(moveImage(images, 0, 1).map((image) => image.id)).toEqual(['b', 'a'])
  expect(images.map((image) => image.id)).toEqual(['a', 'b'])
  expect(moveImage(images, 0, -1)).toBe(images)
})
