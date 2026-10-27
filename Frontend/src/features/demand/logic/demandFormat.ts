// 수요 분석·재고 추천 화면이 함께 쓰는 표시 형식
type Confidence = 'HIGH' | 'LOW' | 'NONE'

export const percent = (rate?: number | null) =>
  rate == null ? '—' : `${Math.round(rate * 100)}%`

export const count = (value?: number | null, unit = '개') =>
  value == null ? '—' : `${value.toLocaleString('ko-KR')}${unit}`

export const CONFIDENCE_LABEL: Record<Confidence, string> = {
  HIGH: '신뢰도 높음',
  LOW: '신뢰도 낮음',
  NONE: '표본 부족',
}
