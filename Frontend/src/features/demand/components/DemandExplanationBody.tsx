import type { DemandExplanationResult } from '../../../api/demand/demand'

type Explanation = NonNullable<DemandExplanationResult['explanation']>

/*
    수요 분석 설명(DEMAND.md 3절)과 생성 단계 추천 근거 설명(8절)이 같은 형식으로 보여 준다.
    생성 단계는 추천하지 못한 이유를 보여 줘야 해서 부족한 데이터도 펼친다(8.5).
 */
export default function DemandExplanationBody({
  explanation,
  includeDataGaps = false,
}: {
  explanation: Explanation
  includeDataGaps?: boolean
}) {
  const sections = [
    ['이유', explanation.reasons],
    ['위험', explanation.risks],
    ...(includeDataGaps
      ? [['부족한 데이터', explanation.dataGaps] as const]
      : []),
    ['권장하지 않는 선택', explanation.notRecommended],
  ] as const
  return (
    <div className="demand-explanation-body">
      <p>{explanation.summary}</p>
      {sections.map(
        ([title, items]) =>
          !!items?.length && (
            <div key={title}>
              <h4>{title}</h4>
              <ul>
                {items.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          ),
      )}
    </div>
  )
}
