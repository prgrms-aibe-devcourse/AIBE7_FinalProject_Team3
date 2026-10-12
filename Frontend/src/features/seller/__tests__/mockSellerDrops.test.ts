import { describe, expect, it } from 'vitest'
import {
  demoSellerDrops,
  dropFromDraft,
  readSellerDropQuery,
} from '../mock/mockSellerDrops'
import type { SellerDraft } from '../mock/mockSellerDrafts'

describe('판매자 DROP Mock 목록', () => {
  it('상태와 0부터 시작하는 페이지를 URL에서 읽는다', () => {
    expect(
      readSellerDropQuery(new URLSearchParams('status=DRAFT&page=2')),
    ).toEqual({ status: 'DRAFT', page: 2 })
    expect(
      readSellerDropQuery(new URLSearchParams('status=BAD&page=-3')),
    ).toEqual({ status: 'ALL', page: 0 })
  })

  it('기존 목록에는 모든 상태가 있고 새 DRAFT는 이미지가 없을 수 있다', () => {
    expect(new Set(demoSellerDrops.map((drop) => drop.status))).toEqual(
      new Set(['DRAFT', 'WISH', 'GRAB', 'ENDED', 'CANCELED']),
    )
    const draft: SellerDraft = {
      id: 'new',
      values: { name: '새 상품' },
      groups: [],
      gallery: [],
      detail: [],
    }
    expect(dropFromDraft(draft, 'grab@example.com')).toMatchObject({
      id: 'new',
      name: '새 상품',
      image: null,
      status: 'DRAFT',
      ownerEmail: 'grab@example.com',
    })
  })
})
