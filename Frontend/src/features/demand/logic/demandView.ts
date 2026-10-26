import axios from 'axios'
import type {
  DemandAnalysis,
  DemandCalculateRequest,
  DemandDecisionRequest,
} from '../../../api/demand/demand'
import { apiErrorCode } from '../../auth/logic/authUser'

export type ConversionBasisChoice = 'AUTO' | 'OWN' | 'MARKET' | 'INPUT'
export type AssumptionForm = {
  basis: ConversionBasisChoice
  ratePercent: string
  safetyPercent: string
}
export type DemandExplanation = {
  summary: string
  reasons: string[]
  risks: string[]
  notRecommended: string[]
}
type DataGap = NonNullable<DemandAnalysis['dataGaps']>[number]
type Confidence = 'HIGH' | 'LOW' | 'NONE'

export class DemandFormError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DemandFormError'
  }
}

// 서버가 정한 소수 둘째 자리 비율을 정수 %로 보여 준다(0.3 → 30%)
export const percent = (rate?: number | null) =>
  rate == null ? '—' : `${Math.round(rate * 100)}%`

export const rateRange = (low?: number | null, high?: number | null) =>
  low == null || high == null
    ? '—'
    : `${Math.round(low * 100)}~${Math.round(high * 100)}%`

export const count = (value?: number | null, unit = '개') =>
  value == null ? '—' : `${value.toLocaleString('ko-KR')}${unit}`

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

export const CONFIDENCE_LABEL: Record<Confidence, string> = {
  HIGH: '신뢰도 높음',
  LOW: '신뢰도 낮음',
  NONE: '표본 부족',
}

export const SOURCE_LABEL: Record<string, string> = {
  OWN: '내 실적',
  MARKET: '시장 평균',
  DEFAULT: '기본값',
  SELLER_INPUT: '직접 입력',
}

// 거절한 계획은 화면에서만 숨긴다. 기록은 서버에 남아 거절 비율 집계에 쓰인다(GR-146)
export const visiblePlans = <T extends { status?: string }>(plans?: T[]) =>
  (plans ?? []).filter((plan) => plan.status !== 'REJECTED')

export const PLAN_STATUS_LABEL: Record<string, string> = {
  DRAFT: '결정 전',
  ACCEPTED: '수락',
  MODIFIED: '수정 후 확정',
  REJECTED: '거절',
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
    hint: '다른 전환율 기준을 고르거나 전환율을 직접 입력해 다시 계산해보세요.',
  },
  SMALL_CONVERSION_SAMPLE: {
    title: '비교한 종료 DROP이 적어 예상 범위가 넓어요.',
    hint: '범위 안에서 재고 부담과 품절 위험을 함께 판단해주세요.',
  },
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

// 기본 제안 대비 바뀐 수량. 어느 한쪽이라도 계산하지 못했으면 비교하지 않는다.
export function planChange(base?: DemandAnalysis, next?: DemandAnalysis) {
  const diff = (a?: number | null, b?: number | null) =>
    a == null || b == null ? null : b - a
  return {
    recommended: diff(base?.plan?.recommended, next?.plan?.recommended),
    expected: diff(base?.plan?.expected, next?.plan?.expected),
  }
}

// 수정 결정은 계획의 모든 옵션을 0 이상 정수로 보낸다(DEMAND.md 2.6)
export function toDecisionRequest(
  status: 'ACCEPTED' | 'MODIFIED' | 'REJECTED',
  reason: string,
  options: { optionId?: number }[] = [],
  quantities: Record<number, string> = {},
): DemandDecisionRequest {
  const trimmed = reason.trim()
  if (trimmed.length > 500)
    throw new DemandFormError('사유는 500자까지 입력할 수 있어요.')
  const request: DemandDecisionRequest = { status }
  if (trimmed && status !== 'ACCEPTED') request.reason = trimmed
  if (status === 'MODIFIED') {
    request.quantities = options.map((option) => {
      const value = (quantities[option.optionId!] ?? '').trim()
      if (!/^\d{1,9}$/.test(value))
        throw new DemandFormError('옵션별 수량을 0 이상 정수로 입력해주세요.')
      return { optionId: option.optionId!, quantity: Number(value) }
    })
  }
  return request
}

const strings = (value: unknown) =>
  Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : []

// LLM 설명은 GENERATED일 때만 오고 형식이 어긋나면 표시하지 않는다(DEMAND.md 3절)
export function readExplanation(value: unknown): DemandExplanation | null {
  if (!value || typeof value !== 'object') return null
  const record = value as Record<string, unknown>
  if (typeof record.summary !== 'string' || !record.summary.trim()) return null
  return {
    summary: record.summary,
    reasons: strings(record.reasons),
    risks: strings(record.risks),
    notRecommended: strings(record.notRecommended),
  }
}

const ERROR_MESSAGES: Record<string, string> = {
  DEMAND_ANALYSIS_UNAVAILABLE:
    '판매 시작 전 WISH DROP만 분석·저장·결정할 수 있어요.',
  DEMAND_PLAN_ALREADY_DECIDED:
    '이미 결정한 계획이에요. 최신 상태를 불러왔어요.',
  DEMAND_PLAN_NOT_FOUND: '판매 계획을 찾을 수 없어요.',
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
  return '처리하지 못했어요. 잠시 후 다시 시도해주세요.'
}
