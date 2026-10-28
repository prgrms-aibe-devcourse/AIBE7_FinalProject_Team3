import type { DemandAnalysis } from '../../../api/demand/demand'
import { currentGap } from '../logic/demandView'

// 준비 권장 수량을 활성 옵션의 현재 수량 비율로 나눈 결과(DEMAND.md 1.6)
export default function DemandOptionPlan({
  analysis,
}: {
  analysis: DemandAnalysis
}) {
  const plan = analysis.plan!
  if (plan.recommended == null || !plan.options?.length) return null
  return (
    <div className="option-stock demand-options">
      <table>
        <caption>옵션별 배분</caption>
        <thead>
          <tr>
            <th>옵션</th>
            <th>현재 준비 수량</th>
            <th>추천 수량</th>
            <th>권장 대비</th>
          </tr>
        </thead>
        <tbody>
          {plan.options.map((option) => (
            <tr key={option.optionId}>
              <td>{option.name || '기본 옵션'}</td>
              <td>{option.currentQuantity}</td>
              <td>{option.recommendedQuantity}</td>
              <td>
                {currentGap(
                  option.recommendedQuantity == null ||
                    option.currentQuantity == null
                    ? null
                    : option.recommendedQuantity - option.currentQuantity,
                )?.option ?? '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
