import { api } from '../client'
import type { components, paths } from '../schema/drop'

export type DropAiDraftImage = components['schemas']['DropAiDraftImage']
export type DropAiDraft = {
  name: string | null
  description: string | null
  categoryId: number | null
  images: { imageId: string; altText: string }[] | null
}

type DraftResponse =
  paths['/api/v1/seller/drop-drafts']['post']['responses'][200]['content']['*/*']

const optionalText = (value: unknown) =>
  value === undefined || value === null || typeof value === 'string'

// 업로드한 GALLERY 이미지로 사진 기반 초안을 받는다(DROP.md 2.8). 저장하지 않으며 항목마다 null일 수 있다
export async function requestDropAiDraft(
  images: DropAiDraftImage[],
  signal?: AbortSignal,
): Promise<DropAiDraft> {
  const { data } = await api.post<DraftResponse>(
    '/seller/drop-drafts',
    { images },
    { signal },
  )
  const draft = data.data
  if (
    data.success !== true ||
    !draft ||
    !optionalText(draft.name) ||
    !optionalText(draft.description) ||
    !(
      draft.categoryId === undefined ||
      draft.categoryId === null ||
      (Number.isSafeInteger(draft.categoryId) && draft.categoryId > 0)
    ) ||
    !(
      draft.images === undefined ||
      draft.images === null ||
      (Array.isArray(draft.images) &&
        draft.images.every(
          (image) =>
            typeof image.imageId === 'string' &&
            typeof image.altText === 'string',
        ))
    )
  )
    throw new Error('DROP 초안 응답 형식이 올바르지 않습니다.')
  return {
    name: draft.name || null,
    description: draft.description || null,
    categoryId: draft.categoryId ?? null,
    images: draft.images?.length
      ? draft.images.map((image) => ({
          imageId: image.imageId!,
          altText: image.altText!,
        }))
      : null,
  }
}
