import type { DemandPlan } from '../../../api/demand/demand'
import { readExplanation } from '../logic/demandView'

// 설명이 없거나 실패하면 영역을 숨기고 계산 결과만 보여 준다(DEMAND.md 2.5)
export default function DemandExplanation({ plan }: { plan: DemandPlan }) {
  if (plan.explanationStatus === 'PENDING')
    return (
      <p className="demand-explanation-pending" role="status">
        설명을 준비하고 있어요.
      </p>
    )
  const explanation =
    plan.explanationStatus === 'GENERATED'
      ? readExplanation(plan.explanation)
      : null
  if (!explanation) return null
  const sections = [
    ['이유', explanation.reasons],
    ['위험', explanation.risks],
    ['권장하지 않는 선택', explanation.notRecommended],
  ] as const
  return (
    <section className="demand-explanation" aria-label="계획 설명">
      <p>{explanation.summary}</p>
      {sections.map(
        ([title, items]) =>
          items.length > 0 && (
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
    </section>
  )
}
