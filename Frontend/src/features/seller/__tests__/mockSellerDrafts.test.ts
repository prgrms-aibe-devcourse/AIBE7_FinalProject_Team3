import { describe, expect, it } from 'vitest'
import { existingSellerDraft } from '../mock/mockSellerDrafts'

describe('판매자 DROP 초안 이미지', () => {
  it('기존 DRAFT 이미지는 GALLERY로 편집하고 공개 DROP은 제외한다', () => {
    expect(existingSellerDraft('401')?.gallery).toHaveLength(1)
    expect(existingSellerDraft('401')?.detail).toHaveLength(0)
    expect(existingSellerDraft('101')).toBeUndefined()
  })
})
