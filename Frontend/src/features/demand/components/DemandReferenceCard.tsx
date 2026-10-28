import { useState } from 'react'
import type {
  DemandAnalysis,
  DemandCalculateRequest,
} from '../../../api/demand/demand'
import EmptyState from '../../../components/EmptyState/EmptyState'
import { dateLabel } from '../../../utils/date'
import { apiErrorCode } from '../../auth/logic/authUser'
import { count } from '../logic/demandFormat'
import {
  demandErrorMessage,
  hoursLabel,
  initialComparison,
  signed,
} from '../logic/demandView'
import { useCalculateDemand, useDemandAnalysis } from '../logic/useDemand'
import DemandAssumptionForm from './DemandAssumptionForm'
import DemandEvidence from './DemandEvidence'
import DemandExplanationPanel from './DemandExplanationPanel'
import DemandOptionPlan from './DemandOptionPlan'
import DemandPlanOverview from './DemandPlanOverview'
import { SimilarSalesSection } from './SimilarSalesPanel'

// WISH 단계 수요 분석을 판매자 DROP 상세에 참고용으로 보여 준다. 저장·결정 없이 재고도 바꾸지 않는다(GR-144, GR-175)
export default function DemandReferenceCard({ dropId }: { dropId: number }) {
  const analysis = useDemandAnalysis(dropId, true)
  const calculate = useCalculateDemand(dropId)
  // 재계산 결과는 그 계산의 바탕이 된 기본 분석(조회 시각)에 묶는다.
  // 기본 분석이 다시 조회되면(WISH 수가 바뀌었을 수 있음) 이전 재계산은 버리고 가정 입력도 처음으로 돌린다
  const [calculated, setCalculated] = useState<{
    baseAt: number
    request: DemandCalculateRequest
    result: DemandAnalysis
  } | null>(null)

  if (analysis.isPending)
    return (
      <section className="panel demand-card" aria-label="수요 분석">
        <h2>수요 분석 (참고용)</h2>
        <div role="status">
          <EmptyState loading title="수요 분석을 계산하는 중이에요." />
        </div>
      </section>
    )
  if (analysis.isError)
    return (
      <section className="panel demand-card" aria-label="수요 분석">
        <h2>수요 분석 (참고용)</h2>
        <div className="seller-drops-result" role="alert">
          <EmptyState title={demandErrorMessage(analysis.error)} />
          {apiErrorCode(analysis.error) !== 'DEMAND_ANALYSIS_UNAVAILABLE' && (
            <button
              type="button"
              className="secondary-button"
              onClick={() => void analysis.refetch()}
            >
              다시 시도
            </button>
          )}
        </div>
      </section>
    )

  const base = analysis.data
  const baseAt = analysis.dataUpdatedAt
  const current = calculated?.baseAt === baseAt ? calculated : null
  const shown = current?.result ?? base
  // AI 설명은 지금 보이는 결과의 가정으로 요청한다. 기본 가정은 빈 본문이다
  const explanationRequest = current?.request ?? {}
  const comparison = initialComparison(shown)
  return (
    <section className="panel demand-card" aria-label="수요 분석">
      <div className="demand-card-heading">
        <div>
          <h2>
            수요 분석 (참고용)
            {base.suggestion?.ready && (
              <span className="demand-ready">지금 확인해 보세요</span>
            )}
          </h2>
          <p>
            WISH {count(base.demand?.activeWishCount, '명')} · 판매 시작까지{' '}
            {hoursLabel(base.demand?.hoursUntilSaleStart)}
            {base.calculatedAt && ` · ${dateLabel(base.calculatedAt)} 계산`}
          </p>
        </div>
      </div>
      <DemandPlanOverview analysis={shown} />
      {comparison && (
        <p className="demand-initial">
          공개할 때 추천 {count(comparison.initial)}
          <small> (과거 판매량 기준)</small> → WISH 기준{' '}
          {count(comparison.current)}
          <small>
            {' '}
            (
            {comparison.difference === 0
              ? '같음'
              : `${signed(comparison.difference)}개`}
            )
          </small>
        </p>
      )}
      <DemandExplanationPanel
        key={`${baseAt}:${JSON.stringify(explanationRequest)}`}
        dropId={dropId}
        request={explanationRequest}
        recalculated={!!current}
      />
      <SimilarSalesSection dropId={dropId} />
      <details className="demand-details">
        <summary>근거와 가정 보기</summary>
        <DemandOptionPlan analysis={shown} />
        <DemandEvidence analysis={shown} />
        <DemandAssumptionForm
          key={baseAt}
          base={base}
          calculated={current?.result ?? null}
          calculating={calculate.isPending}
          onCalculate={async (request) => {
            const result = await calculate.mutateAsync(request)
            setCalculated({ baseAt, request, result })
          }}
          onReset={() => setCalculated(null)}
        />
      </details>
      <ul className="demand-notes">
        {(shown.notes ?? []).map((note) => (
          <li key={note}>{note}</li>
        ))}
        <li>참고용이며 재고를 바꾸지 않아요.</li>
      </ul>
    </section>
  )
}
