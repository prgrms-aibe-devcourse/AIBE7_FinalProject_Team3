import { AxiosError } from 'axios'
import { expect, test } from 'vitest'
import type { DemandAnalysis } from '../../../api/demand/demand'
import {
  DemandFormError,
  demandErrorMessage,
  currentGap,
  hoursLabel,
  initialAssumptionForm,
  marketSampleLabel,
  percent,
  planChange,
  rateRange,
  readExplanation,
  signed,
  toCalculateRequest,
  toDecisionRequest,
} from '../logic/demandView'

const serverError = (code: string, status = 409) =>
  new AxiosError('실패', undefined, undefined, undefined, {
    status,
    data: { error: { code } },
    statusText: '',
    headers: {},
    config: { headers: {} },
  } as never)

const analysis = (recommended: number | null, expected: number | null) =>
  ({
    plan: { recommended, expected },
    assumptions: { safetyStockRate: { value: 0.15, source: 'SELLER_INPUT' } },
  }) as DemandAnalysis

test('비율은 정수 %로, 구간은 하한~상한 %로 표시한다', () => {
  expect(percent(0.3)).toBe('30%')
  expect(percent(0.43)).toBe('43%')
  expect(percent(null)).toBe('—')
  expect(rateRange(0.21, 0.4)).toBe('21~40%')
  expect(rateRange(undefined, 0.4)).toBe('—')
})

test('차이는 부호를 붙이고 음수는 마이너스 기호로 표시한다', () => {
  expect(signed(19)).toBe('+19')
  expect(signed(-1200)).toBe('−1,200')
  expect(signed(0)).toBe('0')
  expect(signed(null)).toBe('—')
})

test('현재 수량 대비 차이는 부족·여유·같음을 말로 나눈다', () => {
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

test('기본 제안 대비 변화량은 둘 다 계산된 수량만 비교한다', () => {
  expect(planChange(analysis(139, 126), analysis(120, 126))).toEqual({
    recommended: -19,
    expected: 0,
  })
  expect(planChange(analysis(139, 126), analysis(null, null))).toEqual({
    recommended: null,
    expected: null,
  })
})

test('수정 결정은 모든 옵션 수량을 정수로 보내고 수락에는 사유를 보내지 않는다', () => {
  const options = [{ optionId: 1 }, { optionId: 2 }]
  expect(
    toDecisionRequest('MODIFIED', ' 재고 부담 ', options, { 1: '5', 2: '0' }),
  ).toEqual({
    status: 'MODIFIED',
    reason: '재고 부담',
    quantities: [
      { optionId: 1, quantity: 5 },
      { optionId: 2, quantity: 0 },
    ],
  })
  expect(toDecisionRequest('ACCEPTED', '메모', options)).toEqual({
    status: 'ACCEPTED',
  })
  expect(toDecisionRequest('REJECTED', '', options)).toEqual({
    status: 'REJECTED',
  })
  expect(() =>
    toDecisionRequest('MODIFIED', '', options, { 1: '5', 2: '-1' }),
  ).toThrow('0 이상 정수')
  expect(() => toDecisionRequest('MODIFIED', '', options, { 1: '5' })).toThrow(
    '0 이상 정수',
  )
  expect(() => toDecisionRequest('REJECTED', '가'.repeat(501))).toThrow('500자')
})

test('설명은 요약이 있는 형식만 읽고 배열의 문자열만 남긴다', () => {
  expect(
    readExplanation({
      summary: '예상 주문 수량은 126개입니다.',
      reasons: ['이유', 3],
      risks: 'x',
    }),
  ).toEqual({
    summary: '예상 주문 수량은 126개입니다.',
    reasons: ['이유'],
    risks: [],
    notRecommended: [],
  })
  expect(readExplanation(null)).toBeNull()
  expect(readExplanation({ summary: ' ' })).toBeNull()
})

test.each([
  ['DEMAND_ANALYSIS_UNAVAILABLE', '판매 시작 전'],
  ['DEMAND_PLAN_ALREADY_DECIDED', '이미 결정한 계획'],
  ['DEMAND_PLAN_NOT_FOUND', '찾을 수 없어요'],
  ['VALIDATION_FAILED', '입력값을 확인'],
])('%s 오류를 구분해 안내한다', (code, text) => {
  expect(demandErrorMessage(serverError(code))).toContain(text)
})

test('알 수 없는 오류는 권한·일반 오류로 안내한다', () => {
  expect(demandErrorMessage(serverError('UNKNOWN', 403))).toBe(
    '판매자 권한이 필요해요.',
  )
  expect(demandErrorMessage(new Error('network'))).toContain('잠시 후')
})
