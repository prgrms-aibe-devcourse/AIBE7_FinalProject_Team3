import type { DraftImage } from './sellerDropForm'

export type ImageKind = 'GALLERY' | 'DETAIL'

export function imageError(file: File, count: number, kind: ImageKind): string {
  const limit = kind === 'GALLERY' ? 5 : 15
  if (count >= limit) return `${kind} 이미지는 ${limit}장까지 추가할 수 있어요.`
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type))
    return 'JPEG, PNG, WebP 이미지만 추가할 수 있어요.'
  if (file.size === 0 || file.size > 5 * 1024 * 1024)
    return '빈 파일은 추가할 수 없고 파일당 5MB 이하만 추가할 수 있어요.'
  if (file.name.length > 255) return '파일명은 255자 이하로 지정해주세요.'
  return ''
}

export function moveImage(
  images: DraftImage[],
  index: number,
  direction: -1 | 1,
) {
  const nextIndex = index + direction
  if (nextIndex < 0 || nextIndex >= images.length) return images
  const next = [...images]
  ;[next[index], next[nextIndex]] = [next[nextIndex], next[index]]
  return next
}
