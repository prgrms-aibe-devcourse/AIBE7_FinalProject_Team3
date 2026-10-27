import { AxiosError } from 'axios'
import { expect, test } from 'vitest'
import type { DemandAnalysis } from '../../../api/demand/demand'
import {
  DemandFormError,
  demandErrorMessage,
  currentGap,
  hoursLabel,
  initialAssumptionForm,
  initialComparison,
  marketSampleLabel,
  planChange,
  rateRange,
  signed,
  toCalculateRequest,
} from '../logic/demandView'

const serverError = (code: string, status = 409) =>
  new AxiosError('실패', undefined, undefined, undefined, {
    status,
    data: { error: { code } },
    statusText: '',
    headers: {},
    config: { headers: {} },
  } as never)

const analysis = (
  recommended: number | null,
  expected: number | null,
  initialRecommended?: number | null,
) =>
  ({
    plan: { recommended, expected },
    assumptions: { safetyStockRate: { value: 0.15, source: 'SELLER_INPUT' } },
    initialRecommendation:
      initialRecommended === undefined
        ? undefined
        : { recommended: initialRecommended ?? undefined },
  }) as DemandAnalysis

test('구간은 하한~상한 %로 표시한다', () => {
  expect(rateRange(0.21, 0.4)).toBe('21~40%')
  expect(rateRange(undefined, 0.4)).toBe('—')
})

test('차이는 부호를 붙이고 음수는 마이너스 기호로 표시한다', () => {
  expect(signed(19)).toBe('+19')
  expect(signed(-1200)).toBe('−1,200')
  expect(signed(0)).toBe('0')
  expect(signed(null)).toBe('—')
})

test('현재 수량 대비 차이는 부족·많음·같음을 말로 나눈다', () => {
  expect(currentGap(31)).toMatchObject({
    title: '권장보다 부족',
    value: '31개',
    option: '31개 부족',
  })
  expect(currentGap(-35)).toMatchObject({
    title: '권장보다 많음',
    value: '35개',
    option: '35개 많음',
  })
  expect(currentGap(0)?.option).toBe('같음')
  expect(currentGap(null)).toBeNull()
})

test('남은 시간은 24시간부터 일 단위로 묶는다', () => {
  expect(hoursLabel(5)).toBe('5시간')
  expect(hoursLabel(48)).toBe('2일')
  expect(hoursLabel(50)).toBe('2일 2시간')
})

test('시장 평균은 표본 구간과 근사 WISH 수로만 표시하고 미달이면 표시하지 않는다', () => {
  expect(marketSampleLabel('10_TO_19', 1740)).toBe(
    'DROP 10~19개 · WISH 약 1,740명',
  )
  expect(marketSampleLabel('20_PLUS', 2000)).toBe(
    'DROP 20개 이상 · WISH 약 2,000명',
  )
  expect(marketSampleLabel('NONE', null)).toBeNull()
})

test('공개 시점 추천과 WISH 기준 추천을 둘 다 있을 때만 비교한다', () => {
  expect(initialComparison(analysis(91, 82, 44))).toEqual({
    initial: 44,
    current: 91,
    difference: 47,
  })
  expect(initialComparison(analysis(91, 82))).toBeNull()
  expect(initialComparison(analysis(91, 82, null))).toBeNull()
  expect(initialComparison(analysis(null, null, 44))).toBeNull()
})

test('가정 입력은 응답의 안전 재고율로 시작하고 자동 기준을 쓴다', () => {
  expect(initialAssumptionForm(analysis(10, 9))).toEqual({
    basis: 'AUTO',
    ratePercent: '',
    safetyPercent: '15',
  })
})

test('가정 입력을 서버 요청으로 바꾸고 기준·직접 입력을 함께 보내지 않는다', () => {
  expect(
    toCalculateRequest({ basis: 'AUTO', ratePercent: '', safetyPercent: '10' }),
  ).toEqual({ safetyStockRate: 0.1 })
  expect(
    toCalculateRequest({
      basis: 'MARKET',
      ratePercent: '40',
      safetyPercent: '0',
    }),
  ).toEqual({ conversionBasis: 'MARKET', safetyStockRate: 0 })
  expect(
    toCalculateRequest({
      basis: 'INPUT',
      ratePercent: '29',
      safetyPercent: '5',
    }),
  ).toEqual({ conversionRate: 0.29, safetyStockRate: 0.05 })
})

test.each([
  [{ basis: 'INPUT', ratePercent: '0', safetyPercent: '10' }, '전환율'],
  [{ basis: 'INPUT', ratePercent: '101', safetyPercent: '10' }, '전환율'],
  [{ basis: 'INPUT', ratePercent: '12.5', safetyPercent: '10' }, '전환율'],
  [{ basis: 'AUTO', ratePercent: '', safetyPercent: '' }, '안전 재고율'],
  [{ basis: 'AUTO', ratePercent: '', safetyPercent: '-1' }, '안전 재고율'],
] as const)('범위를 벗어난 가정 %o는 요청하지 않는다', (form, field) => {
  expect(() => toCalculateRequest(form)).toThrow(DemandFormError)
  expect(() => toCalculateRequest(form)).toThrow(field)
})

test('기본 가정 대비 변화량은 둘 다 계산된 수량만 비교한다', () => {
  expect(planChange(analysis(139, 126), analysis(120, 126))).toEqual({
    recommended: -19,
    expected: 0,
  })
  expect(planChange(analysis(139, 126), analysis(null, null))).toEqual({
    recommended: null,
    expected: null,
  })
})

test.each([
  ['DEMAND_ANALYSIS_UNAVAILABLE', '판매가 시작되어'],
  ['VALIDATION_FAILED', '입력값을 확인'],
  ['DROP_ACCESS_DENIED', '권한이 없어요'],
])('%s 오류를 구분해 안내한다', (code, text) => {
  expect(demandErrorMessage(serverError(code))).toContain(text)
})

test('알 수 없는 오류는 권한·일반 오류로 안내한다', () => {
  expect(demandErrorMessage(serverError('UNKNOWN', 403))).toBe(
    '판매자 권한이 필요해요.',
  )
  expect(demandErrorMessage(new Error('network'))).toContain('잠시 후')
})
