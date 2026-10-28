import type {
  SimilarSales,
  SimilarSalesRequest,
} from '../../../api/demand/similarSales'
import type { DraftGroup } from '../../seller/logic/sellerDropForm'
import { percent } from './demandFormat'
import { toRecommendationInput } from './stockRecommendation'

type Own = NonNullable<SimilarSales['own']>
type Others = NonNullable<SimilarSales['others']>

// 요청 한도(DEMAND.md 7.5)를 넘는 입력은 잘라 보낸다. 저장 검증은 DROP 저장 API가 따로 한다
const MAX_NAME = 200
const MAX_DESCRIPTION = 5000
const MAX_GROUPS = 10
const MAX_GROUP_NAME = 100
const MAX_VALUES = 50
const MAX_VALUE = 100

const text = (value: string | undefined, max: number) => {
  const trimmed = (value ?? '').trim()
  return trimmed ? trimmed.slice(0, max) : undefined
}

/*
    카테고리와 가격이 정해진 활성 SKU가 있어야 요청한다(재고 추천과 같은 조건).
    상품명·설명·옵션은 비슷한 DROP을 고르는 데만 쓰며, 비어 있으면 서버가 카테고리 전체로 넓힌다.
 */
export function toSimilarSalesRequest(
  values: Record<string, string>,
  groups: DraftGroup[],
): SimilarSalesRequest | null {
  const input = toRecommendationInput(values)
  if (!input) return null
  const optionGroups = groups
    .map((group) => ({
      name: group.name.trim().slice(0, MAX_GROUP_NAME),
      values: group.values
        .map((value) => value.label.trim().slice(0, MAX_VALUE))
        .filter(Boolean)
        .slice(0, MAX_VALUES),
    }))
    .filter((group) => group.name && group.values.length)
    .slice(0, MAX_GROUPS)
  return {
    categoryId: input.request.categoryId,
    name: text(values.name, MAX_NAME),
    description: text(values.description, MAX_DESCRIPTION),
    optionGroups: optionGroups.length ? optionGroups : undefined,
    unitPrices: input.request.options.map((option) => option.unitPrice),
  }
}

const othersNoun = (others: Others) =>
  others.selection === 'SIMILAR' ? '비슷한 굿즈' : '같은 카테고리 굿즈'

// 기준과 그 이유를 한 줄로 쓴다(DEMAND.md 7.6)
export function basisLine(data: SimilarSales): string {
  const own = data.own
  const others = data.others!
  if (data.basis === 'OWN')
    return `내 비슷한 DROP ${own?.dropCount ?? 0}개 기준`
  if (data.basis !== 'OTHERS')
    return '비교할 다른 판매자 자료가 아직 부족해요. 아래 내용은 참고만 해 주세요.'
  const noun = othersNoun(others)
  switch (data.reason) {
    case 'OWN_FEW':
      return `내 비슷한 DROP이 ${own?.dropCount ?? 0}개라 다른 판매자의 ${noun}를 기준으로 했어요.`
    case 'OWN_NOT_SIMILAR':
      return `내 지난 DROP 중 비슷한 것이 없어 다른 판매자의 ${noun}를 기준으로 했어요.`
    default:
      return others.scope === 'NEW_SELLERS'
        ? `첫 판매라 처음 판매한 다른 판매자들의 ${noun}를 기준으로 했어요.`
        : `첫 판매라 다른 판매자들의 ${noun}를 기준으로 했어요.`
  }
}

export const minutesLabel = (minutes: number) => {
  if (minutes < 60) return `${minutes}분`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? `${hours}시간 ${rest}분` : `${hours}시간`
}

// "~가 매진됐어요." 또는 "~가 매진됐고, 매진까지 보통 6분 걸렸어요."
const soldOutSentence = (subject: string, minutes?: number | null) =>
  minutes == null
    ? `${subject} 매진됐어요.`
    : `${subject} 매진됐고, 매진까지 보통 ${minutesLabel(minutes)} 걸렸어요.`

// 예: "비슷한 DROP 4개 중 3개가 매진됐고, 매진까지 보통 6분 걸렸어요."
export function ownSentence(own: Own): string {
  const total = own.dropCount ?? 0
  const noun =
    own.selection === 'SIMILAR' ? '비슷한 DROP' : '같은 카테고리 DROP'
  const soldOut = Math.round((own.soldOutRate ?? 0) * total)
  if (!soldOut) return `${noun} ${total}개 중 매진된 DROP은 없었어요.`
  return soldOutSentence(
    `${noun} ${total}개 중 ${soldOut}개가`,
    own.medianSellOutMinutes,
  )
}

const BAND_LABEL: Record<string, string> = {
  '10_TO_19': '10~19개',
  '20_PLUS': '20개 이상',
}

// 다른 판매자는 표본 수를 구간으로만 쓴다
export function othersSentence(others: Others): string {
  const head = `다른 판매자의 ${othersNoun(others)} ${BAND_LABEL[others.dropCountBand!] ?? ''}`
  if (!others.soldOutRate) return `${head} 중 매진된 DROP은 드물었어요.`
  return soldOutSentence(
    `${head} 중 ${percent(others.soldOutRate)}가`,
    others.medianSellOutMinutes,
  )
}

export function salesFacts(sales: Own | Others): string {
  const facts = [`판매율 ${percent(sales.sellThroughRate)}`]
  if (sales.soldPerWish != null)
    facts.push(`판매 시작 WISH 1명당 ${sales.soldPerWish.toFixed(2)}개 판매`)
  return facts.join(' · ')
}
