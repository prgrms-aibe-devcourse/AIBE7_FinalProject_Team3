import type { Page } from '@playwright/test'
import type { components } from '../../src/api/schema/drop'
import { datasetFor, FIXED_NOW } from './api/drop/fixtures'
import { json } from './api/shared'
import { SELLER_DROP_STOCKS } from './api/sellerDropStats'

type Detail = components['schemas']['SellerDropDetailResponse']
type Input = components['schemas']['DropDraftRequest']

function applyDraft(drop: Detail, input: Input): Detail {
  const groups = input.optionGroups?.map((group, index) => ({
    groupId: index + 1,
    name: group.name,
    sortOrder: index,
    values: group.values?.map((value, valueIndex) => ({
      valueId: (index + 1) * 100 + valueIndex + 1,
      value: value.value,
      sortOrder: valueIndex,
    })),
  }))
  return {
    ...drop,
    ...input,
    images: input.images ?? drop.images,
    detailImages: input.detailImages ?? drop.detailImages,
    optionGroups: groups ?? drop.optionGroups,
    options:
      input.options?.map((option, index) => ({
        optionId: index + 1,
        ...option,
        reservedQuantity: 0,
        soldQuantity: 0,
        selections: option.selections?.map((selection) => {
          const groupIndex = input.optionGroups!.findIndex(
            (group) => group.key === selection.groupKey,
          )
          const valueIndex = input.optionGroups![groupIndex].values!.findIndex(
            (value) => value.key === selection.valueKey,
          )
          return {
            groupId: groupIndex + 1,
            valueId: (groupIndex + 1) * 100 + valueIndex + 1,
          }
        }),
      })) ?? drop.options,
  }
}

export async function installSellerDropApi(page: Page) {
  let nextId = 1000
  const drops = new Map<number, Detail>(
    datasetFor('base').map((drop) => [
      drop.dropId,
      {
        dropId: drop.dropId,
        status: drop.status,
        name: drop.name,
        description: drop.description,
        categoryId: drop.categoryId,
        saleStartsAt: drop.saleStartsAt,
        saleEndsAt: drop.saleEndsAt,
        shipping: {
          shippingFee: drop.shippingFee,
          shippingNotice: drop.shippingNotice,
        },
        images: drop.gallery.map((image, index) => ({
          imageId: `00000000-0000-4000-8000-${String(drop.dropId * 100 + index).padStart(12, '0')}`,
          imageUrl: image.imageUrl,
          altText: image.altText,
        })),
        detailImages: [],
        optionGroups: drop.groups,
        options: drop.options.map((option, sortOrder) => ({
          ...option,
          totalQuantity:
            SELLER_DROP_STOCKS[drop.dropId]?.find(
              (stock) => stock.optionId === option.optionId,
            )?.totalStock ?? option.availableStock,
          reservedQuantity:
            SELLER_DROP_STOCKS[drop.dropId]?.find(
              (stock) => stock.optionId === option.optionId,
            )?.reservedStock ?? 0,
          soldQuantity:
            SELLER_DROP_STOCKS[drop.dropId]?.find(
              (stock) => stock.optionId === option.optionId,
            )?.soldStock ?? 0,
          active:
            SELLER_DROP_STOCKS[drop.dropId]?.find(
              (stock) => stock.optionId === option.optionId,
            )?.active ?? option.active !== false,
          sortOrder,
        })),
      },
    ]),
  )
  drops.set(401, {
    ...drops.get(401)!,
    images: [
      {
        imageId: '00000000-0000-4000-8000-000000040100',
        imageUrl: 'https://storage.grab.test/images/401.png',
        altText: '가디건 대표 이미지',
      },
    ],
    options: [
      {
        optionId: 4011,
        selections: [],
        unitPrice: 59000,
        totalQuantity: 10,
        active: true,
      },
    ],
  })
  await page.route('**/api/v1/seller/drops**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (path === '/api/v1/seller/drops' && method === 'GET') {
      const status = url.searchParams.get('status')
      const pageIndex = Number(url.searchParams.get('page') ?? 0)
      const size = Number(url.searchParams.get('size') ?? 20)
      const filtered = [...drops.values()]
        .filter((drop) => !status || drop.status === status)
        .sort((a, b) => b.dropId! - a.dropId!)
      const content = filtered
        .slice(pageIndex * size, (pageIndex + 1) * size)
        .map((drop) => {
          const prices = (drop.options ?? [])
            .filter((option) => option.active !== false)
            .map((option) => option.unitPrice!)
          return {
            dropId: drop.dropId,
            name: drop.name,
            thumbnailUrl: drop.images?.[0]?.imageUrl ?? null,
            status: drop.status,
            minPrice: prices.length ? Math.min(...prices) : null,
            createdAt: FIXED_NOW.toISOString(),
          }
        })
      return json(route, {
        success: true,
        data: {
          content,
          page: pageIndex,
          size,
          totalElements: filtered.length,
          totalPages: Math.ceil(filtered.length / size),
          hasNext: (pageIndex + 1) * size < filtered.length,
        },
      })
    }
    if (path === '/api/v1/seller/drops' && method === 'POST') {
      const drop = applyDraft(
        {
          dropId: ++nextId,
          status: 'DRAFT',
          images: [],
          detailImages: [],
          optionGroups: [],
          options: [],
        },
        route.request().postDataJSON(),
      )
      drops.set(drop.dropId!, drop)
      return json(
        route,
        {
          success: true,
          data: {
            dropId: drop.dropId,
            status: 'DRAFT',
            createdAt: FIXED_NOW.toISOString(),
          },
        },
        201,
      )
    }
    const match = path.match(
      /^\/api\/v1\/seller\/drops\/(\d+)(\/publish|\/cancel|\/stocks|\/wish-count)?$/,
    )
    if (!match) return route.fallback()
    const id = Number(match[1])
    const drop = drops.get(id)
    if (id === 999)
      return json(
        route,
        {
          success: false,
          error: { code: 'DROP_ACCESS_DENIED', fieldErrors: [] },
        },
        403,
      )
    if (!drop)
      return json(
        route,
        { success: false, error: { code: 'DROP_NOT_FOUND', fieldErrors: [] } },
        404,
      )
    if (match[2] === '/wish-count' && method === 'GET')
      return json(route, {
        success: true,
        data: { dropId: id, activeWishCount: 0 },
      })
    if (match[2] === '/stocks' && method === 'GET') {
      if (id !== 401 && id <= 1000) return route.fallback()
      return json(route, {
        success: true,
        data: {
          dropId: id,
          options: (drop.options ?? []).map((option) => ({
            optionId: option.optionId,
            optionName: '기본 옵션',
            totalStock: option.totalQuantity,
            availableStock: option.totalQuantity,
            reservedStock: 0,
            soldStock: 0,
            active: option.active,
          })),
        },
      })
    }
    if (method === 'PATCH') {
      if (drop.status !== 'DRAFT')
        return json(
          route,
          {
            success: false,
            error: { code: 'DROP_NOT_EDITABLE', fieldErrors: [] },
          },
          409,
        )
      drops.set(id, applyDraft(drop, route.request().postDataJSON()))
      return json(route, {
        success: true,
        data: { dropId: id, status: 'DRAFT' },
      })
    }
    if (match[2] === '/cancel' && method === 'POST') {
      drops.set(id, { ...drop, status: 'CANCELED' })
      return json(route, {
        success: true,
        data: { dropId: id, status: 'CANCELED' },
      })
    }
    if (match[2] === '/publish' && method === 'POST') {
      drops.set(id, { ...drop, status: 'WISH' })
      return json(route, {
        success: true,
        data: { dropId: id, status: 'WISH' },
      })
    }
    if (!match[2] && method === 'GET')
      return json(route, { success: true, data: drop })
    return route.fallback()
  })
}
