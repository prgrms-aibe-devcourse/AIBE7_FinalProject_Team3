import type {
  DropAiDraft,
  DropAiDraftImage,
} from '../../../api/seller/dropDrafts'
import { apiErrorCode } from '../../auth/logic/authUser'
import type { DraftImage } from './sellerDropForm'

export const MAX_DRAFT_IMAGES = 3

// 업로드가 끝난 GALLERY 이미지만 앞에서부터 최대 3장 보낸다. 미리보기(blob:)·실패 이미지는 서버가 읽을 수 없다
export function draftSourceImages(gallery: DraftImage[]): DropAiDraftImage[] {
  return gallery
    .filter(
      (image) =>
        !image.uploading && !image.failed && !image.url.startsWith('blob:'),
    )
    .slice(0, MAX_DRAFT_IMAGES)
    .map((image) => ({ imageId: image.id, imageUrl: image.url }))
}

type DraftTarget = {
  values: Record<string, string>
  gallery: DraftImage[]
}

const TEXT_FIELDS = ['name', 'description', 'categoryId'] as const

function draftText(
  draft: DropAiDraft,
  field: (typeof TEXT_FIELDS)[number],
  categoryIds: number[],
) {
  if (field !== 'categoryId') return draft[field]
  return draft.categoryId !== null && categoryIds.includes(draft.categoryId)
    ? String(draft.categoryId)
    : null
}

// 초안이 기존 입력을 바꾸게 되는지. 비어 있거나 같은 값이면 확인 없이 채운다
export function draftOverwrites(
  target: DraftTarget,
  draft: DropAiDraft,
  categoryIds: number[],
) {
  const altTexts = new Map(
    (draft.images ?? []).map((item) => [item.imageId, item.altText]),
  )
  return (
    TEXT_FIELDS.some((field) => {
      const next = draftText(draft, field, categoryIds)
      const current = target.values[field]?.trim() ?? ''
      return next !== null && current !== '' && current !== next
    }) ||
    target.gallery.some(
      (image) =>
        altTexts.has(image.id) &&
        image.altText.trim() !== '' &&
        image.altText !== altTexts.get(image.id),
    )
  )
}

// 초안을 기본 정보·대체 설명에 채운다. overwrite가 false면 비어 있는 칸만 채운다.
// 대체 설명은 이미지 ID로 맞춰 요청 뒤 삭제·순서 변경된 이미지도 올바르게 채운다
export function applyDropAiDraft(
  target: DraftTarget,
  draft: DropAiDraft,
  categoryIds: number[],
  overwrite: boolean,
) {
  const values = { ...target.values }
  let filled = 0
  for (const field of TEXT_FIELDS) {
    const next = draftText(draft, field, categoryIds)
    if (next === null || (!overwrite && values[field]?.trim())) continue
    values[field] = next
    filled++
  }
  const altTexts = new Map(
    (draft.images ?? []).map((item) => [item.imageId, item.altText]),
  )
  const gallery = target.gallery.map((image) => {
    if (!altTexts.has(image.id) || (!overwrite && image.altText.trim()))
      return image
    filled++
    return { ...image, altText: altTexts.get(image.id)! }
  })
  return { values, gallery, filled }
}

export function dropAiDraftErrorMessage(error: unknown) {
  switch (apiErrorCode(error)) {
    case 'AI_DRAFT_LIMIT_EXCEEDED':
      return '오늘 사용할 수 있는 AI 초안 횟수를 모두 썼어요. 내일 다시 시도해주세요.'
    case 'AI_DRAFT_UNAVAILABLE':
      return '지금은 AI 초안을 사용할 수 없어요. 직접 입력해주세요.'
    case 'VALIDATION_FAILED':
      return '업로드한 이미지를 확인할 수 없어요. 이미지를 다시 올려주세요.'
    default:
      return 'AI 초안을 만들지 못했어요. 잠시 후 다시 시도하거나 직접 입력해주세요.'
  }
}
