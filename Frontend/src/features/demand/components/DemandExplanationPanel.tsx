import type { DemandCalculateRequest } from '../../../api/demand/demand'
import {
  demandErrorMessage,
  EXPLANATION_STATUS_TEXT,
} from '../logic/demandView'
import { useDemandExplanation } from '../logic/useDemand'

// 판매자가 누를 때만 지금 보이는 결과(기본 가정 또는 다시 계산한 가정)를 AI로 설명한다(DEMAND.md 3.1).
// 보이는 결과가 바뀌면 카드가 key로 이 영역을 다시 만들어 이전 설명을 지운다.
export default function DemandExplanationPanel({
  dropId,
  request,
  recalculated,
}: {
  dropId: number
  request: DemandCalculateRequest
  recalculated: boolean
}) {
  const explanation = useDemandExplanation(dropId)
  const result = explanation.data
  const sections = result?.explanation
    ? ([
        ['이유', result.explanation.reasons],
        ['위험', result.explanation.risks],
        ['권장하지 않는 선택', result.explanation.notRecommended],
      ] as const)
    : []
  return (
    <section className="demand-explanation" aria-label="AI 설명">
      <div className="demand-explanation-heading">
        <p>
          {recalculated ? '다시 계산한 가정' : '기본 가정'}으로 계산한 결과를
          AI가 문장으로 설명해요. 숫자는 계산 결과와 대조해 맞을 때만 보여 줘요.
        </p>
        <button
          type="button"
          className="secondary-button"
          disabled={explanation.isPending}
          onClick={() => explanation.mutate(request)}
        >
          {explanation.isPending
            ? '설명을 만드는 중'
            : result
              ? '다시 요청'
              : 'AI 설명 보기'}
        </button>
      </div>
      {explanation.isPending && (
        <p className="demand-explanation-pending" role="status">
          설명을 만들고 있어요.
        </p>
      )}
      {explanation.isError && (
        <p className="form-error" role="alert">
          {demandErrorMessage(explanation.error)}
        </p>
      )}
      {result && !explanation.isPending && result.status !== 'GENERATED' && (
        <p className="demand-explanation-pending" role="status">
          {EXPLANATION_STATUS_TEXT[result.status ?? 'FAILED']}
        </p>
      )}
      {result?.explanation && !explanation.isPending && (
        <div className="demand-explanation-body">
          <p>{result.explanation.summary}</p>
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
      )}
    </section>
  )
}
