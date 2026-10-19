import { afterEach, expect, test, vi } from 'vitest'
import type { SellerDropDetail } from '../../../api/seller/drops'
import {
  SellerDropFormError,
  toSellerDraft,
  toSellerDropRequest,
  type SellerDraft,
  rebuildSkuValues,
  validateDropPublish,
} from '../logic/sellerDropForm'

afterEach(() => vi.unstubAllEnvs())

const detail: SellerDropDetail = {
  dropId: 100,
  status: 'DRAFT',
  name: '테스트 DROP',
  description: '상품 설명',
  categoryId: 3,
  saleStartsAt: '2026-10-20T10:00:12.345+09:00',
  saleEndsAt: '2026-10-21T10:00:00+09:00',
  shipping: { shippingFee: 0, shippingNotice: '무료 배송' },
  images: [
    {
      imageId: 'gallery-2',
      imageUrl: 'https://example.com/2.png',
      altText: '',
    },
    {
      imageId: 'gallery-1',
      imageUrl: 'https://example.com/1.png',
      altText: '첫 이미지',
    },
  ],
  detailImages: [
    {
      imageId: 'detail-1',
      imageUrl: 'https://example.com/detail.png',
      altText: '상세',
    },
  ],
  optionGroups: [
    {
      groupId: 11,
      name: '소재',
      sortOrder: 0,
      values: [
        { valueId: 111, value: '코튼', sortOrder: 0 },
        { valueId: 112, value: '린넨', sortOrder: 1 },
      ],
    },
    {
      groupId: 12,
      name: '길이',
      sortOrder: 1,
      values: [
        { valueId: 121, value: '숏', sortOrder: 0 },
        { valueId: 122, value: '롱', sortOrder: 1 },
      ],
    },
  ],
  options: [
    {
      optionId: 1001,
      selections: [
        { groupId: 12, valueId: 122 },
        { groupId: 11, valueId: 112 },
      ],
      unitPrice: 0,
      totalQuantity: 0,
      reservedQuantity: 0,
      soldQuantity: 0,
      active: false,
      sortOrder: 0,
    },
    {
      optionId: 1002,
      selections: [
        { groupId: 11, valueId: 111 },
        { groupId: 12, valueId: 121 },
      ],
      unitPrice: 39000,
      totalQuantity: 10,
      reservedQuantity: 2,
      soldQuantity: 3,
      active: true,
      sortOrder: 1,
    },
  ],
}

const emptyDraft = (): SellerDraft => ({
  id: '',
  values: {},
  groups: [],
  gallery: [],
  detail: [],
})

test('새 기본 SKU의 가격·재고가 모두 비면 빈 DRAFT로 저장한다', () => {
  const draft = emptyDraft()
  draft.values = rebuildSkuValues([], {})
  expect(toSellerDropRequest(draft).options).toEqual([])
  expect(Object.keys(draft.values)).toContain('sku:{}:price')
})

test('조합을 재구성하면 유지된 조합의 가격·재고·활성을 보존하고 제거한 조합은 지운다', () => {
  const draft = toSellerDraft(detail)
  const kept = draft.groups.map((group) => ({
    ...group,
    values: group.values.slice(0, 1),
  }))
  const values = rebuildSkuValues(kept, draft.values)
  expect(
    toSellerDropRequest({ ...draft, groups: kept, values }).options,
  ).toMatchObject([{ unitPrice: 39000, totalQuantity: 10, active: true }])
  expect(
    Object.keys(values).filter((key) => key.endsWith(':price')),
  ).toHaveLength(1)
})

test('공개 필수값은 모두 안내하되 배송비 0은 유효하다', () => {
  const errors = validateDropPublish({ shipping: { shippingFee: 0 } })
  expect(errors.map((error) => error.field)).toEqual(
    expect.arrayContaining([
      'name',
      'description',
      'categoryId',
      'images',
      'options',
      'saleStartsAt',
      'saleEndsAt',
    ]),
  )
  expect(errors.some((error) => error.field === 'shipping.shippingFee')).toBe(
    false,
  )
})

test('옵션 이름·값 중복과 판매 가능한 SKU 누락을 공개 전에 안내한다', () => {
  const request = toSellerDropRequest(toSellerDraft(detail))
  request.optionGroups![1].name = request.optionGroups![0].name
  request.optionGroups![0].values!.push(request.optionGroups![0].values![0])
  request.options!.forEach((option) => {
    option.active = false
  })
  expect(validateDropPublish(request).map((error) => error.field)).toEqual(
    expect.arrayContaining(['optionGroups[0]', 'optionGroups[1]', 'options']),
  )
})

test('상세를 폼과 요청으로 왕복해 이미지·조합·총재고·비활성 상태를 보존한다', () => {
  vi.stubEnv('TZ', 'Asia/Seoul')
  const draft = toSellerDraft(detail)
  expect(draft.id).toBe('100')
  expect(draft.values.saleStartsAt).toBe('2026-10-20T10:00:12.345')
  const request = toSellerDropRequest(draft)
  expect(request).toMatchObject({
    name: detail.name,
    description: detail.description,
    categoryId: 3,
    shipping: detail.shipping,
    images: detail.images,
    detailImages: detail.detailImages,
  })
  expect(request.optionGroups).toEqual([
    {
      key: '11',
      name: '소재',
      sortOrder: 0,
      values: [
        { key: '111', value: '코튼', sortOrder: 0 },
        { key: '112', value: '린넨', sortOrder: 1 },
      ],
    },
    {
      key: '12',
      name: '길이',
      sortOrder: 1,
      values: [
        { key: '121', value: '숏', sortOrder: 0 },
        { key: '122', value: '롱', sortOrder: 1 },
      ],
    },
  ])
  expect(request.options).toEqual([
    {
      selections: [
        { groupKey: '11', valueKey: '112' },
        { groupKey: '12', valueKey: '122' },
      ],
      unitPrice: 0,
      totalQuantity: 0,
      active: false,
      sortOrder: 0,
    },
    {
      selections: [
        { groupKey: '11', valueKey: '111' },
        { groupKey: '12', valueKey: '121' },
      ],
      unitPrice: 39000,
      totalQuantity: 10,
      active: true,
      sortOrder: 1,
    },
  ])
  expect(Date.parse(request.saleStartsAt!)).toBe(
    Date.parse(detail.saleStartsAt!),
  )
})

test.each(['UTC', 'Asia/Seoul', 'America/New_York'])(
  '%s에서도 서버 시각이 변환 왕복 후 같은 순간이다',
  (timezone) => {
    vi.stubEnv('TZ', timezone)
    const request = toSellerDropRequest(toSellerDraft(detail))
    expect(Date.parse(request.saleStartsAt!)).toBe(
      Date.parse(detail.saleStartsAt!),
    )
    expect(Date.parse(request.saleEndsAt!)).toBe(Date.parse(detail.saleEndsAt!))
  },
)

test('필수값이 없는 DRAFT는 0이나 잘못된 날짜로 채우지 않는다', () => {
  const request = toSellerDropRequest(emptyDraft())
  expect(request.categoryId).toBeUndefined()
  expect(request.saleStartsAt).toBeUndefined()
  expect(request.saleEndsAt).toBeUndefined()
  expect(request.shipping?.shippingFee).toBeUndefined()
  expect(request.options).toEqual([])
})

test('옵션 없는 기본 SKU도 가격·재고와 함께 변환한다', () => {
  const draft = toSellerDraft({
    dropId: 101,
    optionGroups: [],
    images: [],
    detailImages: [],
    options: [
      { selections: [], unitPrice: 5000, totalQuantity: 3, active: true },
    ],
  })
  expect(toSellerDropRequest(draft).options).toEqual([
    {
      selections: [],
      unitPrice: 5000,
      totalQuantity: 3,
      active: true,
      sortOrder: 0,
    },
  ])
})

test.each([
  ['categoryId', '0'],
  ['categoryId', 'NaN'],
  ['shippingFee', '-1'],
  ['shippingFee', '1.5'],
  ['shippingFee', '9007199254740992'],
  ['saleStartsAt', '2026-02-30T12:00'],
  ['saleStartsAt', '2026-13-01T12:00'],
  ['saleStartsAt', 'not-a-date'],
])('잘못된 %s=%s를 요청으로 보내지 않는다', (field, value) => {
  const draft = emptyDraft()
  draft.values[field] = value
  expect(() => toSellerDropRequest(draft)).toThrow(SellerDropFormError)
})

test('종료가 시작보다 늦지 않으면 종료 필드 오류를 반환한다', () => {
  const draft = emptyDraft()
  draft.values = {
    saleStartsAt: '2026-10-21T10:00',
    saleEndsAt: '2026-10-20T10:00',
  }
  expect(() => toSellerDropRequest(draft)).toThrow(
    expect.objectContaining({ field: 'saleEndsAt' }),
  )
})

test.each(['', '-1', '1.5', '2147483648'])(
  'SKU 재고 %s를 0으로 대체하거나 잘라서 저장하지 않는다',
  (stock) => {
    const draft = emptyDraft()
    draft.values = { 'sku:{}:price': '1000', 'sku:{}:stock': stock }
    expect(() => toSellerDropRequest(draft)).toThrow(SellerDropFormError)
  },
)

test('삭제된 선택값과 손상된 조합 키를 거부한다', () => {
  const draft = toSellerDraft(detail)
  draft.groups[0].values = []
  expect(() => toSellerDropRequest(draft)).toThrow(/옵션 조합/)
  const invalid = emptyDraft()
  invalid.values = { 'sku:invalid:price': '100', 'sku:invalid:stock': '10' }
  expect(() => toSellerDropRequest(invalid)).toThrow(SellerDropFormError)
})

test('한 용도 삭제 시 다른 용도의 이미지와 대체 설명은 유지한다', () => {
  const draft = toSellerDraft(detail)
  draft.gallery = []
  const request = toSellerDropRequest(draft)
  expect(request.images).toEqual([])
  expect(request.detailImages).toEqual(detail.detailImages)
})

test('업로드 전 미리보기와 실패 이미지는 저장하지 않는다', () => {
  const draft = emptyDraft()
  draft.gallery = [
    { id: 'local', url: 'data:image/png;base64,example', altText: '' },
  ]
  expect(() => toSellerDropRequest(draft)).toThrow(/업로드를 완료/)
  draft.gallery = [
    {
      id: 'failed',
      url: 'https://example.com/image.png',
      altText: '',
      failed: true,
    },
  ]
  expect(() => toSellerDropRequest(draft)).toThrow(/업로드를 완료/)
})

test('손상된 상세 이미지·옵션을 빈 데이터로 덮어쓰지 않는다', () => {
  expect(() => toSellerDraft({ ...detail, images: [{}] })).toThrow(
    /이미지 응답/,
  )
  expect(() =>
    toSellerDraft({ ...detail, options: [{ selections: [] }] }),
  ).toThrow(/옵션 응답/)
  expect(() =>
    toSellerDraft({ ...detail, optionGroups: [{ name: '소재', values: [] }] }),
  ).toThrow(/옵션 그룹 응답/)
})
