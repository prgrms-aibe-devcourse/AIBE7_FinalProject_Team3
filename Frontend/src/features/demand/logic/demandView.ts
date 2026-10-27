import axios from 'axios'
import type {
  DemandAnalysis,
  DemandCalculateRequest,
} from '../../../api/demand/demand'
import { apiErrorCode } from '../../auth/logic/authUser'

export type ConversionBasisChoice = 'AUTO' | 'OWN' | 'MARKET' | 'INPUT'
export type AssumptionForm = {
  basis: ConversionBasisChoice
  ratePercent: string
  safetyPercent: string
}
type DataGap = NonNullable<DemandAnalysis['dataGaps']>[number]

export class DemandFormError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DemandFormError'
  }
}

export const rateRange = (low?: number | null, high?: number | null) =>
  low == null || high == null
    ? '—'
    : `${Math.round(low * 100)}~${Math.round(high * 100)}%`

// 음수 기호는 하이픈 대신 마이너스(−)로 표시해 숫자 폭을 맞춘다
export const signed = (value?: number | null) =>
  value == null
    ? '—'
    : value > 0
      ? `+${value.toLocaleString('ko-KR')}`
      : value < 0
        ? `−${Math.abs(value).toLocaleString('ko-KR')}`
        : '0'

// 준비 권장 수량 − 현재 준비 수량(DROP에 설정한 옵션 수량 합계).
// 부호만으로는 품절 위험인지 재고가 남을 위험인지 읽기 어려워 말로 나눠 보여 준다.
export function currentGap(difference?: number | null) {
  if (difference == null) return null
  const amount = `${Math.abs(difference).toLocaleString('ko-KR')}개`
  if (difference > 0)
    return {
      title: '권장보다 부족',
      value: amount,
      hint: '준비 권장 수량보다 적어 품절될 수 있어요',
      option: `${amount} 부족`,
    }
  if (difference < 0)
    return {
      title: '권장보다 많음',
      value: amount,
      hint: '준비 권장 수량보다 많아 재고가 남을 수 있어요',
      option: `${amount} 많음`,
    }
  return {
    title: '권장과 같음',
    value: '0개',
    hint: '현재 준비 수량이 준비 권장 수량과 같아요',
    option: '같음',
  }
}

export function hoursLabel(hours?: number | null) {
  if (hours == null) return '—'
  if (hours < 24) return `${hours}시간`
  const days = Math.floor(hours / 24)
  return hours % 24 ? `${days}일 ${hours % 24}시간` : `${days}일`
}

export const SOURCE_LABEL: Record<string, string> = {
  OWN: '내 실적',
  MARKET: '시장 평균',
  DEFAULT: '기본값',
  SELLER_INPUT: '직접 입력',
}

// 시장 평균 표본 수는 구간으로만 받는다(DEMAND.md 1.3)
export function marketSampleLabel(band?: string, wishCount?: number | null) {
  const drops =
    band === '10_TO_19'
      ? 'DROP 10~19개'
      : band === '20_PLUS'
        ? 'DROP 20개 이상'
        : null
  if (!drops || wishCount == null) return null
  return `${drops} · WISH 약 ${wishCount.toLocaleString('ko-KR')}명`
}

export const DATA_GAP_TEXT: Record<DataGap, { title: string; hint: string }> = {
  NO_ACTIVE_WISH: {
    title: '활성 WISH가 없어 수량을 계산하지 않았어요.',
    hint: 'WISH가 모이면 다시 확인해주세요.',
  },
  NO_CONVERSION_SAMPLE: {
    title: '비교할 종료 DROP이 부족해 전환율을 정하지 못했어요.',
    hint: '근거와 가정 보기에서 다른 전환율 기준을 고르거나 전환율을 직접 입력해 다시 계산해보세요.',
  },
  SMALL_CONVERSION_SAMPLE: {
    title: '비교한 종료 DROP이 적어 예상 범위가 넓어요.',
    hint: '범위 안에서 재고 부담과 품절 위험을 함께 판단해주세요.',
  },
}

// 공개할 때 남긴 생성 단계 추천(과거 판매량 기준)과 WISH 기준 추천을 나란히 보여 준다.
// 어느 한쪽이라도 수량이 없으면 비교하지 않는다(SellerDropDetailPage.md 수요 분석 참고 카드)
export function initialComparison(analysis?: DemandAnalysis) {
  const initial = analysis?.initialRecommendation?.recommended
  const current = analysis?.plan?.recommended
  if (initial == null || current == null) return null
  return { initial, current, difference: current - initial }
}

export function initialAssumptionForm(
  analysis?: DemandAnalysis,
): AssumptionForm {
  const safety = analysis?.assumptions?.safetyStockRate?.value
  return {
    basis: 'AUTO',
    ratePercent: '',
    safetyPercent: safety == null ? '10' : String(Math.round(safety * 100)),
  }
}

const wholePercent = (value: string, min: number) => {
  const trimmed = value.trim()
  if (!/^\d{1,3}$/.test(trimmed)) return null
  const number = Number(trimmed)
  return number >= min && number <= 100 ? number : null
}

// 서버 입력 범위(DEMAND.md 1.6)를 정수 %로 받는다: 전환율 1~100%, 안전 재고율 0~100%
export function toCalculateRequest(
  form: AssumptionForm,
): DemandCalculateRequest {
  const safety = wholePercent(form.safetyPercent, 0)
  if (safety == null)
    throw new DemandFormError(
      '안전 재고율은 0~100 사이 정수(%)로 입력해주세요.',
    )
  const request: DemandCalculateRequest = { safetyStockRate: safety / 100 }
  if (form.basis === 'OWN' || form.basis === 'MARKET')
    request.conversionBasis = form.basis
  if (form.basis === 'INPUT') {
    const rate = wholePercent(form.ratePercent, 1)
    if (rate == null)
      throw new DemandFormError('전환율은 1~100 사이 정수(%)로 입력해주세요.')
    request.conversionRate = rate / 100
  }
  return request
}

// 설명이 없을 때 이유를 알린다. GENERATED는 설명을 그대로 보여 주므로 문구가 없다(DEMAND.md 3.1)
export const EXPLANATION_STATUS_TEXT: Record<string, string> = {
  DISCARDED:
    '계산 결과와 맞지 않는 설명이 나와 보여 주지 않았어요. 계산 결과를 참고해주세요.',
  FAILED: 'AI 설명을 만들지 못했어요. 잠시 후 다시 시도해주세요.',
  DISABLED: '지금은 AI 설명을 쓸 수 없어요. 계산 결과를 참고해주세요.',
}

// 기본 가정 대비 바뀐 수량. 어느 한쪽이라도 계산하지 못했으면 비교하지 않는다.
export function planChange(base?: DemandAnalysis, next?: DemandAnalysis) {
  const diff = (a?: number | null, b?: number | null) =>
    a == null || b == null ? null : b - a
  return {
    recommended: diff(base?.plan?.recommended, next?.plan?.recommended),
    expected: diff(base?.plan?.expected, next?.plan?.expected),
  }
}

const ERROR_MESSAGES: Record<string, string> = {
  DEMAND_ANALYSIS_UNAVAILABLE: '판매가 시작되어 수요 분석을 볼 수 없어요.',
  DEMAND_EXPLANATION_LIMIT_EXCEEDED:
    '오늘 요청할 수 있는 AI 설명 횟수를 모두 썼어요. 내일 다시 시도해주세요.',
  VALIDATION_FAILED:
    '입력값을 확인해주세요. 전환율은 1~100%, 안전 재고율은 0~100%예요.',
  DROP_ACCESS_DENIED: '이 DROP을 관리할 권한이 없어요.',
  DROP_NOT_FOUND: 'DROP을 찾을 수 없어요.',
}

export function demandErrorMessage(error: unknown) {
  if (error instanceof DemandFormError) return error.message
  const code = apiErrorCode(error)
  if (code && ERROR_MESSAGES[code]) return ERROR_MESSAGES[code]
  const status = axios.isAxiosError(error) ? error.response?.status : undefined
  if (status === 401) return '로그인 후 이용해주세요.'
  if (status === 403) return '판매자 권한이 필요해요.'
  return '수요 분석을 불러오지 못했어요. 잠시 후 다시 시도해주세요.'
}
