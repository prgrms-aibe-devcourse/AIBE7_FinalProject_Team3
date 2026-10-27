import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import {
  requestDropAiDraft,
  type DropAiDraft,
  type DropAiDraftImage,
} from '../../../api/seller/dropDrafts'
import {
  draftSourceImages,
  dropAiDraftErrorMessage,
  MAX_DRAFT_IMAGES,
} from '../logic/dropAiDraft'
import type { DraftImage } from '../logic/sellerDropForm'

type Props = {
  gallery: DraftImage[]
  uploading: boolean
  // 채운 칸 수를 돌려준다. 0이면 채울 수 있는 항목이 없었던 것이다
  onDraft: (draft: DropAiDraft) => number
}

// 사진 기반 DROP 초안(DROP-010). 저장하지 않고 기본 정보 칸에 바로 채우며, 판매자가 확인 후 저장한다
export default function DropAiDraftPanel({
  gallery,
  uploading,
  onDraft,
}: Props) {
  const [result, setResult] = useState('')
  const mutation = useMutation({
    mutationFn: (images: DropAiDraftImage[]) => requestDropAiDraft(images),
    onMutate: () => setResult(''),
    onSuccess: (draft) => {
      const filled = onDraft(draft)
      // 상품을 알아볼 수 없는 사진(풍경 등)이면 모델이 상품명·설명·카테고리를 비워 두고 대체 설명만 준다
      const noProduct =
        draft.name === null &&
        draft.description === null &&
        draft.categoryId === null
      setResult(
        noProduct
          ? `사진에서 상품을 알아보기 어려워 상품명·설명·카테고리는 채우지 못했어요.${filled ? ' 대체 설명만 채웠어요.' : ''} 상품이 잘 보이는 사진으로 다시 시도해주세요.`
          : filled
            ? 'AI 초안으로 작성했어요. 사진에서 확인되지 않는 내용은 직접 확인해주세요.'
            : '채울 수 있는 초안이 없어요. 직접 입력해주세요.',
      )
    },
  })
  const images = draftSourceImages(gallery)

  return (
    <div className="drop-ai-draft">
      <div className="drop-ai-draft-head">
        <p className="form-image-hint">
          {uploading
            ? '이미지 업로드가 끝나면 사진으로 작성할 수 있어요.'
            : images.length
              ? `GALLERY 앞 ${MAX_DRAFT_IMAGES}장으로 상품명·설명·카테고리·대체 설명을 작성해요.`
              : gallery.length
                ? '업로드에 성공한 GALLERY 이미지가 없어요. 실패한 이미지를 다시 올려주세요.'
                : '아래 GALLERY에 사진을 올리면 AI가 기본 정보를 작성해줘요.'}
        </p>
        <button
          type="button"
          className="secondary-button"
          disabled={!images.length || uploading || mutation.isPending}
          onClick={() => mutation.mutate(images)}
        >
          {mutation.isPending ? '작성 중...' : '사진으로 작성'}
        </button>
      </div>
      {mutation.isPending && (
        <p className="form-image-hint" role="status">
          사진을 보고 기본 정보를 작성하고 있어요.
        </p>
      )}
      {result && !mutation.isPending && (
        <p className="form-image-hint" role="status">
          {result}
        </p>
      )}
      {mutation.isError && (
        <p className="form-error" role="alert">
          {dropAiDraftErrorMessage(mutation.error)}
        </p>
      )}
    </div>
  )
}
