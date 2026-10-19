import type {
  SellerDropDetail,
  SellerDropDraftInput,
} from '../../../api/seller/drops'
import type { OptionGroup } from '../../../types/drop'
import { inputDateTime } from '../../../utils/date'
import { optionCombinations } from '../../drop/logic/option'
import type { DropFieldError } from './sellerDropErrors'

export type DraftImage = {
  id: string
  url: string
  altText: string
  failed?: boolean
  uploading?: boolean
}

export type DraftGroup = OptionGroup & { rawValues: string }

export type SellerDraft = {
  id: string
  values: Record<string, string>
  groups: DraftGroup[]
  gallery: DraftImage[]
  detail: DraftImage[]
}

export class SellerDropFormError extends Error {
  constructor(
    readonly field: string,
    message: string,
  ) {
    super(message)
  }
}

function integer(value: string | undefined, field: string, minimum = 0) {
  if (value === undefined || value.trim() === '') return undefined
  const result = Number(value)
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(result) || result < minimum)
    throw new SellerDropFormError(field, '올바른 정수를 입력해주세요.')
  return result
}

function schedule(value: string | undefined, field: string) {
  if (!value) return undefined
  const date = new Date(value)
  if (
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?$/.test(value) ||
    !Number.isFinite(date.getTime()) ||
    inputDateTime(date.toISOString()).slice(0, value.length) !== value
  )
    throw new SellerDropFormError(field, '올바른 판매 시각을 입력해주세요.')
  return date.toISOString()
}

export function toSellerDropRequest(
  draft: Omit<SellerDraft, 'id'>,
): SellerDropDraftInput {
  const { values, groups } = draft
  const saleStartsAt = schedule(values.saleStartsAt, 'saleStartsAt')
  const saleEndsAt = schedule(values.saleEndsAt, 'saleEndsAt')
  if (saleStartsAt && saleEndsAt && saleStartsAt >= saleEndsAt)
    throw new SellerDropFormError(
      'saleEndsAt',
      '판매 종료는 시작보다 늦어야 해요.',
    )

  const options = Object.entries(values)
    .filter(([key]) => key.startsWith('sku:') && key.endsWith(':price'))
    .map(([key, price], sortOrder) => {
      let selections: unknown
      try {
        selections = JSON.parse(key.slice(4, -6))
      } catch {
        throw new SellerDropFormError(
          'options',
          '옵션 조합을 다시 확인해주세요.',
        )
      }
      if (
        !selections ||
        typeof selections !== 'object' ||
        Array.isArray(selections) ||
        Object.keys(selections).length !== groups.length
      )
        throw new SellerDropFormError(
          'options',
          '옵션 조합을 다시 확인해주세요.',
        )
      const selected = selections as Record<string, unknown>
      const unitPrice = integer(price, key)
      const stockKey = `${key.slice(0, -5)}stock`
      const totalQuantity = integer(values[stockKey], stockKey)
      if (unitPrice === undefined && totalQuantity === undefined) return null
      if (
        unitPrice === undefined ||
        totalQuantity === undefined ||
        totalQuantity > 2_147_483_647
      )
        throw new SellerDropFormError(
          'options',
          '각 조합의 가격과 재고를 입력해주세요.',
        )
      return {
        selections: groups.map((group) => {
          const valueKey = selected[group.id]
          if (!group.values.some((value) => value.id === valueKey))
            throw new SellerDropFormError(
              'options',
              '옵션 조합을 다시 확인해주세요.',
            )
          return { groupKey: group.id, valueKey: String(valueKey) }
        }),
        unitPrice,
        totalQuantity,
        active: values[`${key.slice(0, -5)}active`] !== 'false',
        sortOrder,
      }
    })
    .filter((option) => option !== null)

  const images = (items: DraftImage[], field: string) =>
    items.map((image) => {
      if (
        image.failed ||
        image.uploading ||
        !image.id ||
        !image.url ||
        /^(data|blob):/.test(image.url)
      )
        throw new SellerDropFormError(field, '이미지 업로드를 완료해주세요.')
      return { imageId: image.id, imageUrl: image.url, altText: image.altText }
    })

  return {
    name: values.name,
    description: values.description,
    categoryId: integer(values.categoryId, 'categoryId', 1),
    saleStartsAt,
    saleEndsAt,
    shipping: {
      shippingFee: integer(values.shippingFee, 'shipping.shippingFee'),
      shippingNotice: values.shippingNotice,
    },
    images: images(draft.gallery, 'images'),
    detailImages: images(draft.detail, 'detailImages'),
    optionGroups: groups.map((group, sortOrder) => ({
      key: group.id,
      name: group.name,
      sortOrder,
      values: group.values.map((value, valueOrder) => ({
        key: value.id,
        value: value.label,
        sortOrder: valueOrder,
      })),
    })),
    options,
  }
}

export function rebuildSkuValues(
  groups: DraftGroup[],
  values: Record<string, string>,
) {
  const next = Object.fromEntries(
    Object.entries(values).filter(([key]) => !key.startsWith('sku:')),
  )
  const combinations = groups.some((group) => !group.values.length)
    ? []
    : optionCombinations(groups)
  for (const selections of combinations) {
    const key = `sku:${JSON.stringify(selections)}`
    next[`${key}:price`] = values[`${key}:price`] ?? ''
    next[`${key}:stock`] = values[`${key}:stock`] ?? ''
    next[`${key}:active`] = values[`${key}:active`] ?? 'true'
  }
  return next
}

export function validateDropPublish(
  input: SellerDropDraftInput,
): DropFieldError[] {
  const errors: DropFieldError[] = []
  const required: Array<[string, unknown]> = [
    ['name', input.name?.trim()],
    ['description', input.description?.trim()],
    ['categoryId', input.categoryId],
    ['saleStartsAt', input.saleStartsAt],
    ['saleEndsAt', input.saleEndsAt],
    ['shipping.shippingFee', input.shipping?.shippingFee],
    ['shipping.shippingNotice', input.shipping?.shippingNotice?.trim()],
  ]
  for (const [field, value] of required)
    if (value === undefined || value === '')
      errors.push({ field, reason: '공개하려면 입력해주세요.' })
  if (!input.images?.length)
    errors.push({
      field: 'images',
      reason: '공개하려면 GALLERY 이미지를 1장 이상 추가해주세요.',
    })
  if (
    !input.options?.some(
      (option) => option.active !== false && option.totalQuantity > 0,
    )
  )
    errors.push({
      field: 'options',
      reason: '판매 가능한 SKU가 1개 이상 필요해요.',
    })
  const names = new Set<string>()
  for (const [index, group] of (input.optionGroups ?? []).entries()) {
    if (!group.name.trim() || names.has(group.name.trim()))
      errors.push({
        field: `optionGroups[${index}]`,
        reason: '그룹명을 입력하고 중복을 제거해주세요.',
      })
    names.add(group.name.trim())
    const values = (group.values ?? []).map((value) => value.value.trim())
    if (
      !values.length ||
      values.some((value) => !value) ||
      new Set(values).size !== values.length
    )
      errors.push({
        field: `optionGroups[${index}]`,
        reason: '선택값을 입력하고 중복을 제거해주세요.',
      })
  }
  return errors
}

export function toSellerDraft(detail: SellerDropDetail): SellerDraft {
  if (
    !Number.isSafeInteger(detail.dropId) ||
    !detail.dropId ||
    detail.dropId < 1
  )
    throw new Error('판매자 DROP 상세 응답 형식이 올바르지 않습니다.')
  const groups = (detail.optionGroups ?? []).map((group) => {
    if (
      !group.groupId ||
      !group.name ||
      !Array.isArray(group.values) ||
      group.values.some(
        (value) => !value.valueId || typeof value.value !== 'string',
      )
    )
      throw new Error('판매자 DROP 옵션 그룹 응답 형식이 올바르지 않습니다.')
    return {
      id: String(group.groupId),
      name: group.name,
      rawValues: group.values.map((value) => value.value).join(', '),
      values: group.values.map((value) => ({
        id: String(value.valueId),
        label: value.value!,
      })),
    }
  })
  const values: Record<string, string> = {
    name: detail.name ?? '',
    description: detail.description ?? '',
    categoryId:
      detail.categoryId === undefined || detail.categoryId === null
        ? ''
        : String(detail.categoryId),
    saleStartsAt: detail.saleStartsAt ? inputDateTime(detail.saleStartsAt) : '',
    saleEndsAt: detail.saleEndsAt ? inputDateTime(detail.saleEndsAt) : '',
    shippingFee:
      detail.shipping?.shippingFee === undefined ||
      detail.shipping?.shippingFee === null
        ? ''
        : String(detail.shipping.shippingFee),
    shippingNotice: detail.shipping?.shippingNotice ?? '',
  }
  for (const option of detail.options ?? []) {
    const selections = Object.fromEntries(
      groups.map((group) => {
        const selected = option.selections?.find(
          (selection) => String(selection.groupId) === group.id,
        )
        if (
          !selected ||
          !group.values.some((value) => value.id === String(selected.valueId))
        )
          throw new Error('판매자 DROP 옵션 응답 형식이 올바르지 않습니다.')
        return [group.id, String(selected.valueId)]
      }),
    )
    if ((option.selections?.length ?? 0) !== groups.length)
      throw new Error('판매자 DROP 옵션 응답 형식이 올바르지 않습니다.')
    const key = `sku:${JSON.stringify(selections)}`
    if (
      option.unitPrice === undefined ||
      option.unitPrice === null ||
      option.totalQuantity === undefined ||
      option.totalQuantity === null
    )
      throw new Error('판매자 DROP 옵션 응답 형식이 올바르지 않습니다.')
    values[`${key}:price`] = String(option.unitPrice)
    values[`${key}:stock`] = String(option.totalQuantity)
    values[`${key}:active`] = String(option.active ?? true)
  }
  if (!detail.options?.length)
    Object.assign(values, rebuildSkuValues(groups, values))
  const images = (items: SellerDropDetail['images']): DraftImage[] =>
    (items ?? []).map((image) => {
      if (!image.imageId || !image.imageUrl)
        throw new Error('판매자 DROP 이미지 응답 형식이 올바르지 않습니다.')
      return {
        id: image.imageId,
        url: image.imageUrl,
        altText: image.altText ?? '',
      }
    })
  return {
    id: String(detail.dropId),
    values,
    groups,
    gallery: images(detail.images),
    detail: images(detail.detailImages),
  }
}
