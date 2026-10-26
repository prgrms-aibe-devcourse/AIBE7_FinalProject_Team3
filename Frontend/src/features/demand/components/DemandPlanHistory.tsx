import EmptyState from '../../../components/EmptyState/EmptyState'
import type {
  DemandDecisionRequest,
  DemandPlanSummary,
} from '../../../api/demand/demand'
import { dateLabel } from '../../../utils/date'
import {
  count,
  demandErrorMessage,
  PLAN_STATUS_LABEL,
  SOURCE_LABEL,
} from '../logic/demandView'
import { useDemandPlan } from '../logic/useDemand'
import DemandDecisionForm from './DemandDecisionForm'
import DemandExplanation from './DemandExplanation'
import DemandPlanOverview from './DemandPlanOverview'

function PlanDetail({
  planId,
  decidable,
  onDecide,
}: {
  planId: string
  decidable: boolean
  onDecide: (planId: string, request: DemandDecisionRequest) => Promise<unknown>
}) {
  const query = useDemandPlan(planId)
  if (query.isPending)
    return (
      <div role="status">
        <EmptyState loading title="저장한 계획을 불러오는 중이에요." />
      </div>
    )
  if (query.isError)
    return (
      <div className="seller-drops-result" role="alert">
        <EmptyState
          title="저장한 계획을 불러오지 못했어요."
          description={demandErrorMessage(query.error)}
        />
        <button
          type="button"
          className="secondary-button"
          onClick={() => void query.refetch()}
        >
          다시 시도
        </button>
      </div>
    )
  const plan = query.data
  const decision = plan.decision
  const source = plan.analysis?.assumptions?.conversionRate?.source
  return (
    <div className="demand-plan-detail">
      <p className="demand-hint">
        {dateLabel(plan.createdAt!)} 저장 시점 계산 · 전환율 기준{' '}
        {source ? SOURCE_LABEL[source] : '없음'}
      </p>
      <DemandPlanOverview analysis={plan.analysis!} />
      <DemandExplanation plan={plan} />
      {decision ? (
        <dl className="demand-decision-result">
          <div>
            <dt>결정</dt>
            <dd>
              {PLAN_STATUS_LABEL[decision.status!]} ·{' '}
              {dateLabel(decision.decidedAt!)}
            </dd>
          </div>
          {!!decision.quantities?.length && (
            <div>
              <dt>확정 수량</dt>
              <dd>
                {decision.quantities
                  .map(
                    (item) =>
                      `${
                        plan.analysis?.plan?.options?.find(
                          (option) => option.optionId === item.optionId,
                        )?.name || '기본 옵션'
                      } ${item.quantity}개`,
                  )
                  .join(' · ')}
              </dd>
            </div>
          )}
          {decision.reason && (
            <div>
              <dt>사유</dt>
              <dd>{decision.reason}</dd>
            </div>
          )}
        </dl>
      ) : decidable ? (
        <DemandDecisionForm
          plan={plan}
          onDecide={(request) => onDecide(planId, request)}
        />
      ) : (
        <p className="demand-hint">
          판매가 시작된 DROP의 계획은 결정을 기록할 수 없어요.
        </p>
      )}
    </div>
  )
}

export default function DemandPlanHistory({
  plans,
  openPlanId,
  decidable,
  onToggle,
  onDecide,
}: {
  plans: DemandPlanSummary[]
  openPlanId: string | null
  decidable: boolean
  onToggle: (planId: string) => void
  onDecide: (planId: string, request: DemandDecisionRequest) => Promise<unknown>
}) {
  if (!plans.length)
    return (
      <EmptyState
        title="저장한 계획이 없어요."
        description="가정을 확인한 뒤 계획을 저장하면 여기에 기록돼요."
      />
    )
  return (
    <ol className="demand-history">
      {plans.map((plan) => {
        const open = plan.planId === openPlanId
        return (
          <li key={plan.planId}>
            <button
              type="button"
              className="demand-history-toggle"
              aria-expanded={open}
              onClick={() => onToggle(plan.planId!)}
            >
              <span className={`status-pill ${plan.status?.toLowerCase()}`}>
                {PLAN_STATUS_LABEL[plan.status!]}
              </span>
              <strong>
                준비 권장{' '}
                {plan.recommended == null
                  ? '계산 안 됨'
                  : count(plan.recommended)}
              </strong>
              <small>
                {dateLabel(plan.createdAt!)} 저장
                {plan.decidedAt && ` · ${dateLabel(plan.decidedAt)} 결정`}
              </small>
              <span aria-hidden="true">{open ? '▴' : '▾'}</span>
            </button>
            {open && (
              <PlanDetail
                planId={plan.planId!}
                decidable={decidable}
                onDecide={onDecide}
              />
            )}
          </li>
        )
      })}
    </ol>
  )
}
