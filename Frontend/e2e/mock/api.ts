import type { Page, Route } from '@playwright/test'

// Mock 일정이 Date.now() 기준이라 시각을 고정해야 D-day·날짜 표시가 매번 같다.
export const FIXED_NOW = new Date('2026-10-01T10:00:00+09:00')
const BASE = FIXED_NOW.getTime()
const at = (days: number) => new Date(BASE + days * 86_400_000).toISOString()
const daysAgo = (days: number) => at(-days)

export const LONG_PRODUCT_NAME =
  '매일 오래 들고 다닐 수 있는 한정판 캔버스 토트와 데일리 스트랩 특별 구성 상품'

type Value = { valueId: number; value: string }
type Group = { groupId: number; name: string; values: Value[] }
type Option = {
  optionId: number
  selections: { groupId: number; valueId: number }[]
  unitPrice: number
  availableStock: number
  active?: boolean
}
type Image = { imageUrl: string; altText: string }

export type FixtureDrop = {
  dropId: number
  name: string
  brand: string
  description: string
  thumbnailUrl: string | null
  categoryId: number
  categoryName: string
  status: 'WISH' | 'GRAB' | 'ENDED' | 'CANCELED' | 'DRAFT'
  wishCount: number
  saleStartsAt: string
  saleEndsAt: string
  shippingFee: number
  shippingNotice: string
  publishedAt: string
  createdAt: string
  soldQuantity: number
  gallery: Image[]
  detailImages: Image[]
  groups: Group[]
  options: Option[]
  // 공개 목록에 노출할지. 상세 전용 예시(품절·종료)는 false다.
  listed: boolean
}

export const CATEGORIES = [
  { categoryId: 1, code: 'FASHION', name: '패션' },
  { categoryId: 2, code: 'LIVING', name: '리빙' },
]

const WISH_NOTICE =
  'WISH는 구매, 재고 예약 또는 구매 우선권을 보장하지 않습니다.'

const NOTICE_SHIPPING = '결제 완료 후 3~5 영업일 이내 출고'
const NOTICE_MADE = '주문 후 순차 제작, 5영업일 이내 출고'

const image = (imageUrl: string, altText: string): Image => ({
  imageUrl,
  altText,
})

const toteImage =
  'https://bojashop.com/cdn/shop/files/SHPH8541.jpg?v=1718237431&width=1400'
const mugImage =
  'https://images.unsplash.com/photo-1514228742587-6b1558fcca3d?auto=format&fit=crop&w=1200&q=85'
const mugExtraImage =
  'https://images.unsplash.com/photo-1577937927133-66ef06acdf18?auto=format&fit=crop&w=1200&q=85'
const teeImage =
  'https://images.unsplash.com/photo-1678951564425-ae20e499c48f?auto=format&fit=crop&w=1200&q=85'
const clubImage =
  'https://images.unsplash.com/photo-1576566588028-4147f3842f27?auto=format&fit=crop&w=1200&q=85'
const clubDetailImage =
  'https://images.unsplash.com/photo-1529139574466-a303027c1d8b?auto=format&fit=crop&w=1200&q=85'
const morningImage =
  'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?auto=format&fit=crop&w=1200&q=85'
const canvasBagImage =
  'https://images.unsplash.com/photo-1594223274512-ad4803739b7c?auto=format&fit=crop&w=1200&q=85'

export function baseDrops(): FixtureDrop[] {
  return [
    {
      dropId: 101,
      name: '매일을 담는 캔버스 토트',
      brand: 'SLOW OBJECT',
      description:
        '가볍게 들고 오래 쓰는 데일리 백. 취향에 맞는 소재와 스트랩 길이를 골라보세요.',
      thumbnailUrl: toteImage,
      categoryId: 1,
      categoryName: '패션',
      status: 'WISH',
      wishCount: 248,
      saleStartsAt: at(3),
      saleEndsAt: at(10),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(7),
      createdAt: daysAgo(9),
      soldQuantity: 0,
      gallery: [image(toteImage, '매일을 담는 캔버스 토트 대표 이미지')],
      detailImages: [],
      groups: [
        {
          groupId: 1,
          name: '소재',
          values: [
            { valueId: 1, value: '캔버스' },
            { valueId: 2, value: '워시드 코튼' },
          ],
        },
        {
          groupId: 2,
          name: '스트랩 길이',
          values: [
            { valueId: 1, value: '스탠다드' },
            { valueId: 2, value: '롱' },
          ],
        },
      ],
      options: [
        {
          optionId: 1011,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 29000,
          availableStock: 20,
        },
        {
          optionId: 1012,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 31000,
          availableStock: 12,
        },
        {
          optionId: 1013,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 32000,
          availableStock: 8,
        },
        {
          optionId: 1014,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 34000,
          availableStock: 6,
        },
      ],
      listed: true,
    },
    {
      dropId: 102,
      name: '나의 작은 휴식, 데일리 머그',
      brand: 'ROOM FOR REST',
      description:
        '손에 편안하게 감기는 머그와 선물 포장을 자유롭게 조합할 수 있어요.',
      thumbnailUrl: mugImage,
      categoryId: 2,
      categoryName: '리빙',
      status: 'WISH',
      wishCount: 186,
      saleStartsAt: at(5),
      saleEndsAt: at(12),
      shippingFee: 3000,
      shippingNotice: NOTICE_MADE,
      publishedAt: daysAgo(5),
      createdAt: daysAgo(7),
      soldQuantity: 0,
      gallery: [
        image(mugImage, '나의 작은 휴식, 데일리 머그 대표 이미지'),
        image(mugExtraImage, '데일리 머그의 손잡이와 표면 질감'),
      ],
      detailImages: [
        image(mugImage, '데일리 머그의 전체 형태와 유약 색상'),
        image(mugExtraImage, '데일리 머그의 손잡이와 표면 질감'),
      ],
      groups: [
        {
          groupId: 1,
          name: '유약',
          values: [
            { valueId: 1, value: '밀크 화이트' },
            { valueId: 2, value: '샌드 베이지' },
          ],
        },
        {
          groupId: 2,
          name: '포장',
          values: [
            { valueId: 1, value: '간단 포장' },
            { valueId: 2, value: '선물 상자' },
          ],
        },
      ],
      options: [
        {
          optionId: 1021,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 24000,
          availableStock: 18,
        },
        {
          optionId: 1022,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 27000,
          availableStock: 8,
        },
        {
          optionId: 1023,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 24000,
          availableStock: 14,
        },
        {
          optionId: 1024,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 27000,
          availableStock: 6,
        },
      ],
      listed: true,
    },
    {
      dropId: 103,
      name: '플레이리스트 그래픽 티셔츠',
      brand: 'OFF THE RECORD',
      description:
        '좋아하는 음악을 입는 방법. 핏과 프린트 조합을 직접 선택해보세요.',
      thumbnailUrl: teeImage,
      categoryId: 1,
      categoryName: '패션',
      status: 'WISH',
      wishCount: 312,
      saleStartsAt: at(0.3),
      saleEndsAt: at(9),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(3),
      createdAt: daysAgo(5),
      soldQuantity: 0,
      gallery: [image(teeImage, '플레이리스트 그래픽 티셔츠 대표 이미지')],
      detailImages: [],
      groups: [
        {
          groupId: 1,
          name: '핏',
          values: [
            { valueId: 1, value: '레귤러' },
            { valueId: 2, value: '오버사이즈' },
          ],
        },
        {
          groupId: 2,
          name: '프린트',
          values: [
            { valueId: 1, value: '그린 믹스' },
            { valueId: 2, value: '블루 믹스' },
          ],
        },
      ],
      options: [
        {
          optionId: 1031,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 39000,
          availableStock: 10,
        },
        {
          optionId: 1032,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 39000,
          availableStock: 12,
        },
        {
          optionId: 1033,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 42000,
          availableStock: 8,
        },
        {
          optionId: 1034,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 42000,
          availableStock: 4,
        },
      ],
      listed: true,
    },
    {
      dropId: 201,
      name: '뮤직 클럽 티셔츠 · 리미티드',
      brand: 'OFF THE RECORD',
      description: '이번 시즌, 음악을 좋아하는 우리를 위한 한정 컬렉션입니다.',
      thumbnailUrl: clubImage,
      categoryId: 1,
      categoryName: '패션',
      status: 'GRAB',
      wishCount: 210,
      saleStartsAt: at(-1),
      saleEndsAt: at(6),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(8),
      createdAt: daysAgo(10),
      soldQuantity: 27,
      gallery: [image(clubImage, '뮤직 클럽 티셔츠 · 리미티드 대표 이미지')],
      detailImages: [
        image(clubDetailImage, '뮤직 클럽 티셔츠의 프린트와 소재'),
      ],
      groups: [
        {
          groupId: 1,
          name: '착용감',
          values: [
            { valueId: 1, value: '릴랙스' },
            { valueId: 2, value: '와이드' },
          ],
        },
        {
          groupId: 2,
          name: '소매',
          values: [
            { valueId: 1, value: '하프' },
            { valueId: 2, value: '롱' },
          ],
        },
      ],
      options: [
        {
          optionId: 2011,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 39000,
          availableStock: 8,
        },
        {
          optionId: 2012,
          selections: [
            { groupId: 1, valueId: 1 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 43000,
          availableStock: 5,
        },
        {
          optionId: 2013,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 1 },
          ],
          unitPrice: 41000,
          availableStock: 6,
        },
        {
          optionId: 2014,
          selections: [
            { groupId: 1, valueId: 2 },
            { groupId: 2, valueId: 2 },
          ],
          unitPrice: 45000,
          availableStock: 0,
        },
      ],
      listed: true,
    },
    {
      dropId: 202,
      name: '슬로우 모닝 머그',
      brand: 'ROOM FOR REST',
      description:
        '조용한 아침, 좋아하는 음료와 함께 나만의 시간을 만들어보세요.',
      thumbnailUrl: morningImage,
      categoryId: 2,
      categoryName: '리빙',
      status: 'GRAB',
      wishCount: 142,
      saleStartsAt: at(-1),
      saleEndsAt: at(0.6),
      shippingFee: 3000,
      shippingNotice: NOTICE_MADE,
      publishedAt: daysAgo(4),
      createdAt: daysAgo(6),
      soldQuantity: 12,
      gallery: [image(morningImage, '슬로우 모닝 머그 대표 이미지')],
      detailImages: [],
      groups: [
        {
          groupId: 1,
          name: '손잡이 형태',
          values: [
            { valueId: 1, value: '라운드' },
            { valueId: 2, value: '스퀘어' },
          ],
        },
      ],
      options: [
        {
          optionId: 2021,
          selections: [{ groupId: 1, valueId: 1 }],
          unitPrice: 24000,
          availableStock: 20,
        },
        {
          optionId: 2022,
          selections: [{ groupId: 1, valueId: 2 }],
          unitPrice: 26000,
          availableStock: 12,
        },
      ],
      listed: true,
    },
    {
      dropId: 203,
      name: '에브리데이 캔버스 백',
      brand: 'SLOW OBJECT',
      description: '어떤 하루에도 자연스럽게 어울리는 담백한 캔버스 백입니다.',
      thumbnailUrl: canvasBagImage,
      categoryId: 1,
      categoryName: '패션',
      status: 'GRAB',
      wishCount: 175,
      saleStartsAt: at(-2),
      saleEndsAt: at(4),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(6),
      createdAt: daysAgo(8),
      soldQuantity: 18,
      gallery: [image(canvasBagImage, '에브리데이 캔버스 백 대표 이미지')],
      detailImages: [],
      groups: [],
      options: [
        {
          optionId: 2031,
          selections: [],
          unitPrice: 29000,
          availableStock: 10,
        },
      ],
      listed: true,
    },
    {
      dropId: 204,
      name: '에브리데이 캔버스 백 · 품절',
      brand: 'SLOW OBJECT',
      description: '품절 상태를 확인하기 위한 예시 상품입니다.',
      thumbnailUrl: canvasBagImage,
      categoryId: 1,
      categoryName: '패션',
      status: 'GRAB',
      wishCount: 175,
      saleStartsAt: at(-2),
      saleEndsAt: at(4),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(6),
      createdAt: daysAgo(8),
      soldQuantity: 0,
      gallery: [image(canvasBagImage, '에브리데이 캔버스 백 대표 이미지')],
      detailImages: [],
      groups: [],
      options: [
        {
          optionId: 2031,
          selections: [],
          unitPrice: 29000,
          availableStock: 0,
        },
      ],
      listed: false,
    },
    {
      dropId: 301,
      name: '에브리데이 캔버스 백 · 판매 종료',
      brand: 'SLOW OBJECT',
      description: '판매가 종료된 예시 상품입니다.',
      thumbnailUrl: canvasBagImage,
      categoryId: 1,
      categoryName: '패션',
      status: 'ENDED',
      wishCount: 175,
      saleStartsAt: at(-10),
      saleEndsAt: at(-3),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(12),
      createdAt: daysAgo(14),
      soldQuantity: 0,
      gallery: [image(canvasBagImage, '에브리데이 캔버스 백 대표 이미지')],
      detailImages: [],
      groups: [],
      options: [
        {
          optionId: 2031,
          selections: [],
          unitPrice: 29000,
          availableStock: 10,
        },
      ],
      listed: false,
    },
    {
      dropId: 401,
      name: '오버핏 니트 가디건',
      brand: 'SLOW OBJECT',
      description: '두꺼운 직조로 뜨개 만든 가디건. 색상은 조율 중입니다.',
      thumbnailUrl: toteImage,
      categoryId: 1,
      categoryName: '패션',
      status: 'DRAFT',
      wishCount: 0,
      saleStartsAt: at(14),
      saleEndsAt: at(21),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(0),
      createdAt: daysAgo(0),
      soldQuantity: 0,
      gallery: [],
      detailImages: [],
      groups: [],
      options: [],
      listed: false,
    },
    {
      dropId: 403,
      name: '리미티드 키링 세트',
      brand: 'SLOW OBJECT',
      description: '제작 일정 문제로 출시를 취소한 상품입니다.',
      thumbnailUrl: toteImage,
      categoryId: 2,
      categoryName: '리빙',
      status: 'CANCELED',
      wishCount: 64,
      saleStartsAt: at(-20),
      saleEndsAt: at(-13),
      shippingFee: 3000,
      shippingNotice: NOTICE_SHIPPING,
      publishedAt: daysAgo(22),
      createdAt: daysAgo(24),
      soldQuantity: 0,
      gallery: [],
      detailImages: [],
      groups: [],
      options: [
        {
          optionId: 4031,
          selections: [],
          unitPrice: 42000,
          availableStock: 30,
        },
      ],
      listed: false,
    },
  ]
}

const activeOptions = (drop: FixtureDrop) =>
  drop.options.filter((option) => option.active !== false)

const totalStock = (drop: FixtureDrop) =>
  activeOptions(drop).reduce((sum, option) => sum + option.availableStock, 0)

const isSoldOut = (drop: FixtureDrop) =>
  activeOptions(drop).length === 0 || totalStock(drop) === 0

const minPrice = (drop: FixtureDrop): number | null => {
  const prices = activeOptions(drop).map((option) => option.unitPrice)
  return prices.length ? Math.min(...prices) : null
}

const listItem = (drop: FixtureDrop) => ({
  dropId: drop.dropId,
  name: drop.name,
  brandName: drop.brand,
  thumbnailUrl: drop.thumbnailUrl,
  minPrice: minPrice(drop),
  category: { categoryId: drop.categoryId, name: drop.categoryName },
  status: drop.status,
  soldOut: isSoldOut(drop),
  availableStock: totalStock(drop),
  wishCount: drop.wishCount,
  saleStartsAt: drop.saleStartsAt,
  saleEndsAt: drop.saleEndsAt,
})

const actions = (drop: FixtureDrop) => {
  const now = FIXED_NOW.getTime()
  const started = Date.parse(drop.saleStartsAt) <= now
  const running = Date.parse(drop.saleEndsAt) > now
  const soldOut = isSoldOut(drop)
  if (drop.status === 'ENDED' || drop.status === 'CANCELED')
    return { wishable: false, wishCancelable: false, orderable: false }
  if (drop.status === 'WISH')
    return started
      ? {
          wishable: false,
          wishCancelable: false,
          orderable: running && !soldOut,
        }
      : { wishable: true, wishCancelable: true, orderable: false }
  return {
    wishable: false,
    wishCancelable: false,
    orderable: running && !soldOut,
  }
}

const detailBody = (drop: FixtureDrop) => ({
  dropId: drop.dropId,
  name: drop.name,
  brandName: drop.brand,
  description: drop.description,
  imageUrls: drop.gallery.map((item) => item.imageUrl),
  galleryImages: drop.gallery,
  detailImages: drop.detailImages,
  minPrice: minPrice(drop),
  category: { categoryId: drop.categoryId, name: drop.categoryName },
  status: drop.status,
  soldOut: isSoldOut(drop),
  wishCount: drop.wishCount,
  wishNotice: WISH_NOTICE,
  saleStartsAt: drop.saleStartsAt,
  saleEndsAt: drop.saleEndsAt,
  shipping: {
    shippingFee: drop.shippingFee,
    shippingNotice: drop.shippingNotice,
  },
  optionGroups: drop.groups,
  options: activeOptions(drop).map((option) => ({
    optionId: option.optionId,
    selections: option.selections,
    unitPrice: option.unitPrice,
    availableStock: option.availableStock,
    soldOut: option.availableStock === 0,
  })),
  actions: actions(drop),
  serverTime: FIXED_NOW.toISOString(),
})

const stockBody = (drop: FixtureDrop) => ({
  dropId: drop.dropId,
  options: activeOptions(drop).map((option) => ({
    optionId: option.optionId,
    availableStock: option.availableStock,
    soldOut: option.availableStock === 0,
  })),
  serverTime: FIXED_NOW.toISOString(),
})

type SortField =
  'publishedAt' | 'createdAt' | 'saleStartsAt' | 'wishCount' | 'soldQuantity'

const sortValue = (drop: FixtureDrop, field: SortField) =>
  field === 'wishCount'
    ? drop.wishCount
    : field === 'soldQuantity'
      ? drop.soldQuantity
      : Date.parse(drop[field])

function listResponse(dataset: FixtureDrop[], params: URLSearchParams) {
  const status = params.get('status')
  const categoryId = params.get('categoryId')
  const keyword = (params.get('keyword') ?? '').trim().toLowerCase()
  const soldOutParam = params.get('soldOut')
  const sort = params.get('sort') ?? 'publishedAt,desc'
  const page = Number(params.get('page') ?? '0')
  const size = Number(params.get('size') ?? '24')
  const [field, direction] = sort.split(',') as [SortField, 'asc' | 'desc']

  const popular = field === 'wishCount' || field === 'soldQuantity'
  let items = dataset.filter(
    (drop) =>
      drop.listed && drop.status !== 'DRAFT' && drop.status !== 'CANCELED',
  )
  // 인기 정렬은 저장 상태가 아니라 서버 시각의 탐색 단계로 후보를 정하므로
  // status 파라미터로 WISH를 먼저 걸러내지 않는다(soldQuantity는 WISH·GRAB 모두 후보).
  if (!popular && status) items = items.filter((drop) => drop.status === status)
  if (categoryId)
    items = items.filter((drop) => String(drop.categoryId) === categoryId)
  if (keyword)
    items = items.filter((drop) => drop.name.toLowerCase().includes(keyword))
  if (soldOutParam)
    items = items.filter(
      (drop) => isSoldOut(drop) === (soldOutParam === 'true'),
    )

  if (popular) {
    const now = FIXED_NOW.getTime()
    items = items.filter((drop) => {
      if (field === 'wishCount')
        return drop.status === 'WISH' && Date.parse(drop.saleStartsAt) > now
      return (
        (drop.status === 'WISH' || drop.status === 'GRAB') &&
        Date.parse(drop.saleStartsAt) <= now &&
        now < Date.parse(drop.saleEndsAt) &&
        totalStock(drop) > 0
      )
    })
  }

  items = [...items].sort((a, b) => {
    const diff = sortValue(a, field) - sortValue(b, field)
    const directed =
      field === 'wishCount' || field === 'soldQuantity'
        ? -diff
        : direction === 'asc'
          ? diff
          : -diff
    return (
      directed ||
      Date.parse(b.publishedAt) - Date.parse(a.publishedAt) ||
      b.dropId - a.dropId
    )
  })

  const totalElements = items.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  return {
    content: items.slice(page * size, page * size + size).map(listItem),
    page,
    size,
    totalElements,
    totalPages: totalElements === 0 ? 0 : totalPages,
    hasNext: (page + 1) * size < totalElements,
  }
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })

export type Scenario =
  'base' | 'empty' | 'failure' | 'many' | 'states' | 'transition'

function datasetFor(scenario: Scenario): FixtureDrop[] {
  const dataset = baseDrops()
  if (scenario === 'transition') {
    const sample = dataset.find((drop) => drop.dropId === 203) as FixtureDrop
    return [
      ...dataset,
      {
        ...sample,
        dropId: 104,
        name: '전환 지연 한정 티셔츠',
        categoryId: 1,
        categoryName: '패션',
        status: 'WISH',
        wishCount: 5,
        saleStartsAt: at(-0.5),
        saleEndsAt: at(2),
        publishedAt: daysAgo(1),
        soldQuantity: 40,
        groups: [],
        options: [
          {
            optionId: 1041,
            selections: [],
            unitPrice: 39000,
            availableStock: 5,
          },
        ],
        listed: true,
      },
      {
        ...sample,
        dropId: 302,
        name: '종료 지연 리미티드 백',
        status: 'GRAB',
        wishCount: 3,
        saleStartsAt: at(-3),
        saleEndsAt: at(-1),
        publishedAt: daysAgo(5),
        soldQuantity: 1,
        groups: [],
        options: [
          {
            optionId: 3021,
            selections: [],
            unitPrice: 29000,
            availableStock: 5,
          },
        ],
        listed: true,
      },
    ]
  }
  if (scenario === 'many') {
    const sample = dataset.find((drop) => drop.dropId === 103)
    return [
      ...dataset.filter((drop) => drop.status !== 'WISH'),
      ...Array.from({ length: 30 }, (_, index) => ({
        ...(sample as FixtureDrop),
        dropId: 2000 + index,
        name: `페이지 테스트 상품 ${index + 1}`,
        wishCount: index,
        publishedAt: daysAgo(1),
        listed: true,
      })),
    ]
  }
  if (scenario === 'states') {
    const longName = LONG_PRODUCT_NAME
    return dataset.map((drop) => {
      if (drop.dropId === 101)
        return { ...drop, name: longName, thumbnailUrl: null }
      if (drop.dropId === 201)
        return {
          ...drop,
          name: longName,
          options: drop.options.map((option) => ({
            ...option,
            availableStock: 0,
          })),
        }
      if (drop.dropId === 203)
        return {
          ...drop,
          options: drop.options.map((option) => ({
            ...option,
            active: false,
          })),
        }
      return drop
    })
  }
  return dataset
}

async function handleRoute(
  route: Route,
  scenario: Scenario,
  failureStatus: 'all' | 'GRAB' | null,
) {
  const url = new URL(route.request().url())
  const path = url.pathname
  const dataset = datasetFor(scenario)

  if (path === '/api/v1/categories') {
    await json(route, { success: true, data: CATEGORIES })
    return
  }
  if (path === '/api/v1/drops') {
    const status = url.searchParams.get('status')
    const fails =
      failureStatus === 'all' || (failureStatus === 'GRAB' && status === 'GRAB')
    if (fails)
      await json(route, { success: false, message: 'INVALID_REQUEST' }, 500)
    else if (scenario === 'empty' && status === 'GRAB')
      await json(route, {
        success: true,
        data: {
          content: [],
          page: 0,
          size: 24,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
        },
      })
    else
      await json(route, {
        success: true,
        data: listResponse(dataset, url.searchParams),
      })
    return
  }
  const detail = path.match(/^\/api\/v1\/drops\/(\d+)$/)
  if (detail) {
    const drop = dataset.find((item) => item.dropId === Number(detail[1]))
    if (!drop || drop.status === 'DRAFT')
      await json(route, { success: false, message: 'DROP_NOT_FOUND' }, 404)
    else await json(route, { success: true, data: detailBody(drop) })
    return
  }
  const stocks = path.match(/^\/api\/v1\/drops\/(\d+)\/stocks$/)
  if (stocks) {
    const drop = dataset.find((item) => item.dropId === Number(stocks[1]))
    if (!drop || drop.status === 'DRAFT')
      await json(route, { success: false, message: 'DROP_NOT_FOUND' }, 404)
    else await json(route, { success: true, data: stockBody(drop) })
    return
  }
  await route.fallback()
}

// 모든 소비자 화면이 공개 API만 호출하도록 기본 응답을 고정한다.
export async function installPublicApi(page: Page) {
  await page.route('**/api/v1/**', (route) => handleRoute(route, 'base', null))
}

export async function installHomeScenario(
  page: Page,
  scenario: 'empty' | 'failure' | 'many',
) {
  await page.route('**/api/v1/**', (route) =>
    handleRoute(route, scenario, scenario === 'failure' ? 'GRAB' : null),
  )
}

export async function installCatalogScenario(page: Page, scenario: Scenario) {
  await page.route('**/api/v1/**', (route) =>
    handleRoute(route, scenario, scenario === 'failure' ? 'all' : null),
  )
}
