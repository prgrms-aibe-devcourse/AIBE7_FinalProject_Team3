import type { DemandAnalysis } from '../../../api/demand/demand'
import { count, percent } from '../logic/demandFormat'
import { currentGap, DATA_GAP_TEXT } from '../logic/demandView'

// 예상 판매량과 준비 권장 수량은 쓰임이 달라 이름과 설명을 나눠 보여 준다(GR-144)
export default function DemandPlanOverview({
  analysis,
}: {
  analysis: DemandAnalysis
}) {
  const plan = analysis.plan!
  const safety = analysis.assumptions?.safetyStockRate?.value
  const gaps = analysis.dataGaps ?? []
  const calculated = plan.recommended != null
  const gap = currentGap(plan.differenceFromCurrent)
  return (
    <div className="demand-plan">
      {calculated ? (
        <dl className="demand-metrics">
          <div>
            <dt>예상 판매량</dt>
            <dd>{count(plan.expected)}</dd>
            <small>
              범위 {plan.low}~{plan.high}개 · WISH 기준 예측
            </small>
          </div>
          <div className="is-primary">
            <dt>준비 권장 수량</dt>
            <dd>{count(plan.recommended)}</dd>
            <small>안전 재고 {percent(safety)} 포함</small>
          </div>
          <div>
            <dt>현재 준비 수량</dt>
            <dd>{count(plan.currentTotalQuantity)}</dd>
            <small>DROP에 설정한 활성 옵션 수량 합계</small>
          </div>
          {gap && (
            <div>
              <dt>{gap.title}</dt>
              <dd>{gap.value}</dd>
              <small>{gap.hint}</small>
            </div>
          )}
        </dl>
      ) : (
        <p className="demand-uncalculated">
          수량을 계산하지 않았어요. 현재 준비 수량은{' '}
          {count(plan.currentTotalQuantity)}예요.
        </p>
      )}
      {gaps.length > 0 && (
        <ul className="demand-gaps">
          {gaps.map((item) => (
            <li key={item}>
              <strong>{DATA_GAP_TEXT[item].title}</strong>
              <span>{DATA_GAP_TEXT[item].hint}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
