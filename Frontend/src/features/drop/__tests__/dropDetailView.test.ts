import { describe, expect, it } from 'vitest'
import type { components } from '../../../api/schema/drop'
import { toDropDetail, toOrderDrop } from '../logic/dropDetailView'

type PublicDropDetail = components['schemas']['PublicDropDetailResponse']

const detail = (overrides: Partial<PublicDropDetail> = {}): PublicDropDetail =>
  ({
    dropId: 100,
    name: '한정판 스니커즈',
    description: '상품 설명',
    imageUrls: ['https://example.com/1.jpg'],
    galleryImages: [{ imageUrl: 'https://example.com/1.jpg', altText: '정면' }],
    detailImages: [{ imageUrl: 'https://example.com/d.jpg' }],
    minPrice: 129000,
    status: 'GRAB',
    soldOut: false,
    wishCount: 152,
    wishNotice: 'WISH 안내',
    saleStartsAt: '2026-09-20T10:00:00+09:00',
    saleEndsAt: '2026-09-20T12:00:00+09:00',
    shipping: { shippingFee: 3000, shippingNotice: '3~5 영업일' },
    optionGroups: [
      { groupId: 11, name: '소재', values: [{ valueId: 111, value: '코튼' }] },
    ],
    options: [
      {
        optionId: 1001,
        selections: [{ groupId: 11, valueId: 111 }],
        unitPrice: 129000,
        availableStock: 8,
      },
    ],
    actions: { wishable: false, wishCancelable: false, orderable: true },
    serverTime: '2026-09-20T10:10:00+09:00',
    ...overrides,
  }) as PublicDropDetail

describe('공개 상세 응답 → 상세 모델', () => {
  it('옵션 그룹·SKU·이미지·행동을 화면 형태로 옮긴다', () => {
    const view = toDropDetail(detail({ brandName: 'SLOW OBJECT' }))
    expect(view.brand).toBe('SLOW OBJECT')
    expect(view.optionGroups).toEqual([
      { id: '11', name: '소재', values: [{ id: '111', label: '코튼' }] },
    ])
    expect(view.skus).toEqual([
      { id: 1001, selections: { 11: '111' }, price: 129000, stock: 8 },
    ])
    expect(view.galleryImages).toEqual([
      { imageUrl: 'https://example.com/1.jpg', altText: '정면' },
    ])
    expect(view.detailImages[0].altText).toBe('한정판 스니커즈 상세 이미지')
    expect(view.actions).toEqual({
      wishable: false,
      wishCancelable: false,
      orderable: true,
    })
    expect(view.category).toBe('')
  })

  it('옵션 가용 재고가 없으면 stock을 비워 둔다', () => {
    const view = toDropDetail(
      detail({ options: [{ optionId: 5, unitPrice: 1000 }] }),
    )
    expect(view.skus).toEqual([
      { id: 5, selections: {}, price: 1000, stock: undefined },
    ])
  })

  it('galleryImages가 없으면 imageUrls로 대체하고 첫 이미지를 대표로 쓴다', () => {
    const view = toDropDetail(
      detail({ galleryImages: [], imageUrls: ['https://example.com/a.jpg'] }),
    )
    expect(view.image).toBe('https://example.com/a.jpg')
    expect(view.galleryImages).toEqual([
      {
        imageUrl: 'https://example.com/a.jpg',
        altText: '한정판 스니커즈 이미지 1',
      },
    ])
  })

  it('actions가 없으면 모두 false, 종료 상태를 보존한다', () => {
    const view = toDropDetail(detail({ actions: undefined, status: 'ENDED' }))
    expect(view.actions).toEqual({
      wishable: false,
      wishCancelable: false,
      orderable: false,
    })
    expect(view.status).toBe('ENDED')
  })
})

describe('상세 모델 → 데모 주문 Drop', () => {
  it('브랜드는 비워 두고 SKU 재고는 0으로 채운다', () => {
    const orderDrop = toOrderDrop(toDropDetail(detail()))
    expect(orderDrop.brand).toBe('')
    expect(orderDrop.skus[0].stock).toBe(8)
    expect(orderDrop.image).toBe('https://example.com/1.jpg')
  })
})
